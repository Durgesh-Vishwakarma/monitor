// Quick script to create MVC directory structure
const fs = require('fs');
const path = require('path');

const dirs = [
  'src/config',
  'src/models',
  'src/controllers',
  'src/services',
  'src/middleware',
  'src/routes',
  'src/utils'
];

dirs.forEach(dir => {
  const fullPath = path.join(__dirname, dir);
  fs.mkdirSync(fullPath, { recursive: true });
  console.log('✓ Created:', dir);
});

console.log('\n✅ MVC directory structure created successfully!');
