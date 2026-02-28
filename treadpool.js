// threadpool.js
// Run with: UV_THREADPOOL_SIZE=2 node threadpool.js
// Try also: UV_THREADPOOL_SIZE=8 node threadpool.js

const crypto = require('crypto');

function job(i) {
    const start = Date.now();
    crypto.pbkdf2('password', 'salt', 200_000, 64, 'sha512', () => {
        console.log(`Job ${i} done in ${Date.now() - start} ms`);
    }); 
}

for (let i = 1; i <= 8; i++) {
    job(i);
}

console.log('queued 8 pbkdf2 jobs');