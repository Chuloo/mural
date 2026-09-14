export function validateSchema(value, schema, path = "$", errors = []) {
  if (!schema || typeof schema !== "object") return errors;
  if (schema.enum && !schema.enum.some((candidate) => Object.is(candidate, value))) errors.push(`${path}: enum`);
  if (schema.const !== undefined && !Object.is(schema.const, value)) errors.push(`${path}: const`);
  const type = schema.type;
  if (type === "object") {
    if (value === null || typeof value !== "object" || Array.isArray(value)) errors.push(`${path}: object`);
    else {
      for (const required of schema.required ?? []) if (!Object.hasOwn(value, required)) errors.push(`${path}.${required}: required`);
      for (const [key, child] of Object.entries(value)) {
        if (schema.properties && Object.hasOwn(schema.properties, key)) validateSchema(child, schema.properties[key], `${path}.${key}`, errors);
        else if (schema.additionalProperties === false) errors.push(`${path}.${key}: additional property`);
      }
    }
  } else if (type === "array") {
    if (!Array.isArray(value)) errors.push(`${path}: array`);
    else { if (schema.minItems !== undefined && value.length < schema.minItems) errors.push(`${path}: minItems`); if (schema.maxItems !== undefined && value.length > schema.maxItems) errors.push(`${path}: maxItems`); value.forEach((v, i) => validateSchema(v, schema.items, `${path}[${i}]`, errors)); }
  } else if (type === "string") {
    if (typeof value !== "string") errors.push(`${path}: string`); else { if (schema.minLength !== undefined && value.length < schema.minLength) errors.push(`${path}: minLength`); if (schema.maxLength !== undefined && value.length > schema.maxLength) errors.push(`${path}: maxLength`); if (schema.pattern && !(new RegExp(schema.pattern).test(value))) errors.push(`${path}: pattern`); }
  } else if (type === "number" || type === "integer") {
    if (typeof value !== "number" || !Number.isFinite(value) || (type === "integer" && !Number.isInteger(value))) errors.push(`${path}: ${type}`); else { if (schema.minimum !== undefined && value < schema.minimum) errors.push(`${path}: minimum`); if (schema.maximum !== undefined && value > schema.maximum) errors.push(`${path}: maximum`); }
  } else if (type === "boolean" && typeof value !== "boolean") errors.push(`${path}: boolean`);
  return errors;
}

export function assertSchema(value, schema) {
  const errors = validateSchema(value, schema);
  if (errors.length) throw Object.assign(new Error("model output failed response schema"), { code: "SCHEMA_INVALID", details: errors.slice(0, 8) });
}
