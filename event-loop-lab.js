// event-loop-lab.js
// Run with: node event-loop-lab.js
// Optional: node --trace-event-categories node.perf event-loop-lab.js
const fs = require('fs');

function log(msg) {
    const t = new Date().toISOString().slice(11, 23);
    console.log(`${t} - ${msg}`);
}

log('A) Start of script');

setTimeout(() => {
    log('B) Inside setTimeout(0) callback');
}, 0);

setImmediate(() => {
    log('C) Inside setImmediate callback');
});

fs.readFile(__filename, () => {
    log('D) Inside fs.readFile callback');

    setTimeout(() => {
        log('F) Inside setTimeout(0) callback inside fs.readFile callback');
    }, 0);
    setImmediate(() => {
        log('G) Inside setImmediate callback inside fs.readFile callback');
    });

    process.nextTick(() => {
        log('H) Inside process.nextTick callback inside fs.readFile callback');
    });
    Promise.resolve().then(() => {
        log('I) Inside Promise.then callback inside fs.readFile callback');
    });
});

process.nextTick(() => {
    log('E) Inside process.nextTick callback');
});
Promise.resolve().then(() => {
    log('J) Inside Promise.then callback');
});

log('K) End of script');