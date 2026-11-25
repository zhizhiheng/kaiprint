// Node tests use Ajv directly. The local web server serves its standalone ESM
// output at this same path, so the browser enforces the same published schemas as the CLI.
import Ajv from "ajv/dist/2020.js";
import standaloneCode from "ajv/dist/standalone/index.js";
import ucs2 from "ajv/dist/runtime/ucs2length.js";
import schema from "../formats/kaiphoto-template-v1.schema.json" with { type: "json" };

import projectSchema from "../formats/kaiphoto-project-v2.schema.json" with { type: "json" };

const names = {
  validateTemplate: "kaiphoto-template-v1.schema.json",
  validateProject: "kaiphoto-project-v2.schema.json",
};
const ajv = new Ajv({ code: { source: true, esm: true } });
ajv.addSchema(schema, names.validateTemplate);
ajv.addSchema(projectSchema, names.validateProject);
export const validateTemplate = ajv.getSchema(names.validateTemplate);
export const validateProject = ajv.getSchema(names.validateProject);

export function browserValidatorSource() {
  // These schemas use one Ajv runtime helper. Inline its official Unicode
  // implementation rather than sending a CommonJS require to the browser.
  return standaloneCode(ajv, names).replaceAll(
    ucs2.default.code,
    `(${ucs2.default.toString()})`,
  );
}
