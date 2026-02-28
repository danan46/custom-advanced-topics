const http = require("http");
const { Worker } = require("worker_threads");
const path = require("path");

function runCpuJob(ms) {
  return new Promise((resolve, reject) => {
    const worker = new Worker(path.join(__dirname, "worker.js"), {
      workerData: { ms },
    });

    worker.on("message", (result) => resolve(result));
    worker.on("error", reject);
    worker.on("exit", (code) => {
      if (code !== 0) reject(new Error(`Worker stopped with exit code ${code}`));
    });
  });
}

http
  .createServer(async (req, res) => {
    const started = Date.now();
    try {
      const result = await runCpuJob(5000);
      const elapsed = Date.now() - started;
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ ok: true, result, elapsedMs: elapsed }));
    } catch (e) {
      res.writeHead(500, { "content-type": "application/json" });
      res.end(JSON.stringify({ ok: false, error: e.message }));
    }
  })
  .listen(3000, () => console.log("listening on :3000"));