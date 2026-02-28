// starvation.js
// Run with: node starvation.js
let count = 0;

setTimeout(() => {
    console.log('timer fired (this may be delayed due to event loop starvation)');
}, 0);

function tick() {
    count++;
    if (count % 1e6 === 0) {
        console.log(`tick ${count}`);
    }
    process.nextTick(tick);
}

tick();