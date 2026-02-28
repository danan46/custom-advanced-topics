const { workerData, parentPort } = require("worker_threads");

function burnCpu(ms) {
  const start = Date.now();
  while (Date.now() - start < ms) {}
  return { burnedMs: ms };
}

const result = burnCpu(workerData.ms);
parentPort.postMessage(result);