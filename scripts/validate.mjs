import Ajv from "ajv";
import addFormats from "ajv-formats";
import fs from "node:fs";

export function validateKeyboards() {
  const schema = JSON.parse(fs.readFileSync("keyboards.schema.json","utf8"));
  const data   = JSON.parse(fs.readFileSync("keyboards.json","utf8"));

  const ajv = new Ajv({ strict: false, allErrors: true }); // draft-07 by default
  addFormats(ajv); // adds "uri", "date", etc.

  const validate = ajv.compile(schema);
  const valid = validate(data);
  return [valid, validate.errors]
}

// If run directly via `node scripts/validate.mjs`
if (import.meta.url === `file://${process.argv[1]}`) {
  const [valid, errors] = validateKeyboards();
  if (!valid) {
    console.error("Validation failed:");
    console.error(JSON.stringify(errors, null, 2));
    process.exit(1);
  } else {
    console.log("OK");
  }
}
