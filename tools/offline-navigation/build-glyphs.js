#!/usr/bin/env node

/* Generate only the Unicode ranges used by the Russian offline map style. */
const fs = require("fs");
const path = require("path");
const fontnik = require("fontnik");

if (process.argv.length !== 4) {
  console.error("Usage: build-glyphs <font.ttf> <output-dir>");
  process.exit(2);
}

const fontPath = process.argv[2];
const outputRoot = path.resolve(process.argv[3]);
const fontStack = "Noto Sans Regular";
const ranges = [
  [0, 255],       // Latin, digits, and common punctuation.
  [1024, 1279],   // Cyrillic.
  [8192, 8447],   // General punctuation and symbols.
];
const outputDir = path.join(outputRoot, fontStack);
fs.mkdirSync(outputDir, { recursive: true });
const font = fs.readFileSync(fontPath);

function buildRange(start, end) {
  return new Promise((resolve, reject) => {
    fontnik.range({ font, start, end }, (error, data) => {
      if (error) {
        reject(error);
        return;
      }
      const output = path.join(outputDir, `${start}-${end}.pbf`);
      fs.writeFileSync(output, data);
      console.log(`${output} ${data.length}`);
      resolve();
    });
  });
}

Promise.all(ranges.map(([start, end]) => buildRange(start, end))).catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
