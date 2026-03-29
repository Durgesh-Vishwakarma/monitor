/**
 * MVC Refactoring Setup Script
 * Creates directory structure for the refactored backend
 */

const fs = require('fs');
const path = require('path');

console.log('🚀 Setting up MVC directory structure...\n');

// Define directory structure
const directories = [
  'src',
  'src/config',
  'src/models',
  'src/controllers',
  'src/services',
  'src/middleware',
  'src/routes',
  'src/utils'
];

// Create all directories
directories.forEach(dir => {
  const fullPath = path.join(__dirname, dir);
  if (!fs.existsSync(fullPath)) {
    fs.mkdirSync(fullPath, { recursive: true });
    console.log(`✓ Created: ${dir}`);
  } else {
    console.log(`  Exists: ${dir}`);
  }
});

console.log('\n✅ Directory structure ready!');
console.log('\nNext: Run the refactoring to populate these directories with code.\n');
