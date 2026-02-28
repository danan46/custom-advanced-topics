const crypto = require("crypto");
const express = require("express");
const { Pool } = require("pg");
const { randomUUID } = require("crypto");

const app = express();
app.use(express.json());

const pool = new Pool(/* ... */);

function stableStringify(obj) {
  // In production use a stable stringify library.
  return JSON.stringify(obj, Object.keys(obj).sort());
}

function requestHash(body) {
  return crypto.createHash("sha256").update(stableStringify(body)).digest("hex");
}

// Mock provider call
async function chargeProvider({ amountCents }) {
  // simulate success/timeout
  if (Math.random() < 0.2) {
    const err = new Error("timeout");
    err.code = "ETIMEDOUT";
    throw err;
  }
  return { providerChargeId: "ch_" + Math.random().toString(16).slice(2) };
}

app.post("/payments/charge", async (req, res) => {
  const merchantId = req.header("x-merchant-id");
  const idemKey = req.header("x-idempotency-key");

  if (!merchantId || !idemKey) {
    return res.status(400).json({ error: "Missing x-merchant-id or x-idempotency-key" });
  }

  const reqHash = requestHash(req.body);

  const client = await pool.connect();
  try {
    await client.query("BEGIN");

    // 1) Try insert idempotency record
    const insert = await client.query(
      `
      INSERT INTO idempotency_keys
        (merchant_id, idempotency_key, request_hash, status)
      VALUES
        ($1, $2, $3, 'IN_PROGRESS')
      ON CONFLICT (merchant_id, idempotency_key)
      DO NOTHING
      RETURNING merchant_id, idempotency_key, request_hash, status, payment_id, response_code, response_body
      `,
      [merchantId, idemKey, reqHash]
    );

    if (insert.rowCount === 0) {
      // 2) Duplicate key path: fetch existing row and decide response deterministically
      const existing = await client.query(
        `
        SELECT request_hash, status, response_code, response_body, payment_id
        FROM idempotency_keys
        WHERE merchant_id = $1 AND idempotency_key = $2
        `,
        [merchantId, idemKey]
      );

      const row = existing.rows[0];

      if (row.request_hash !== reqHash) {
        await client.query("ROLLBACK");
        return res.status(409).json({ error: "Idempotency-Key reuse with different payload" });
      }

      if (row.status === "SUCCEEDED") {
        await client.query("COMMIT");
        return res.status(row.response_code || 200).json(row.response_body);
      }

      if (row.status === "IN_PROGRESS") {
        await client.query("COMMIT");
        return res.status(202).json({ paymentId: row.payment_id, status: "IN_PROGRESS" });
      }

      // FAILED: return stored failure (policy choice)
      await client.query("COMMIT");
      return res.status(row.response_code || 502).json(row.response_body || { status: "FAILED" });
    }

    // 3) First time key seen: create payment row PENDING
    const paymentId = randomUUID();
    const { customerId, amountCents, currency, paymentMethodId } = req.body;

    await client.query(
      `
      INSERT INTO payments
        (payment_id, merchant_id, customer_id, amount_cents, currency, payment_method_id, status)
      VALUES
        ($1, $2, $3, $4, $5, $6, 'PENDING')
      `,
      [paymentId, merchantId, customerId, amountCents, currency, paymentMethodId]
    );

    // link payment_id to idempotency record
    await client.query(
      `
      UPDATE idempotency_keys
      SET payment_id = $1, updated_at = now()
      WHERE merchant_id = $2 AND idempotency_key = $3
      `,
      [paymentId, merchantId, idemKey]
    );

    // Commit early so other retries can see IN_PROGRESS + payment_id
    await client.query("COMMIT");

    // 4) Call provider outside the transaction (avoid holding locks during network call)
    try {
      const providerRes = await chargeProvider({ amountCents });

      // Persist success
      const successBody = { paymentId, status: "SUCCEEDED", providerChargeId: providerRes.providerChargeId };

      await pool.query(
        `UPDATE payments SET status='SUCCEEDED', provider_charge_id=$1, updated_at=now() WHERE payment_id=$2`,
        [providerRes.providerChargeId, paymentId]
      );

      await pool.query(
        `
        UPDATE idempotency_keys
        SET status='SUCCEEDED', response_code=201, response_body=$1::jsonb, updated_at=now()
        WHERE merchant_id=$2 AND idempotency_key=$3
        `,
        [JSON.stringify(successBody), merchantId, idemKey]
      );

      return res.status(201).json(successBody);
    } catch (e) {
      if (e.code === "ETIMEDOUT") {
        // Keep IN_PROGRESS; a reconciliation job will finalize later
        return res.status(202).json({ paymentId, status: "IN_PROGRESS" });
      }

      const failBody = { paymentId, status: "FAILED", error: e.message };

      await pool.query(`UPDATE payments SET status='FAILED', updated_at=now() WHERE payment_id=$1`, [paymentId]);
      await pool.query(
        `
        UPDATE idempotency_keys
        SET status='FAILED', response_code=502, response_body=$1::jsonb, updated_at=now()
        WHERE merchant_id=$2 AND idempotency_key=$3
        `,
        [JSON.stringify(failBody), merchantId, idemKey]
      );

      return res.status(502).json(failBody);
    }
  } catch (err) {
    try { await client.query("ROLLBACK"); } catch {}
    return res.status(500).json({ error: "Internal error" });
  } finally {
    client.release();
  }
});