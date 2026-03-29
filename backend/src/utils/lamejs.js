/**
 * Load lamejs IIFE bundle (not CJS-friendly).
 */

const fs = require("fs");
const path = require("path");

const lamePath = path.join(
  __dirname,
  "..",
  "..",
  "node_modules",
  "@breezystack",
  "lamejs",
  "dist",
  "lamejs.iife.js",
);

const lameCode = fs.readFileSync(lamePath, "utf8");
const { Mp3Encoder } = new Function(`${lameCode}; return lamejs;`)();

module.exports = {
  Mp3Encoder,
};
