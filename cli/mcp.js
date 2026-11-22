#!/usr/bin/env node
import { startMcp } from "./mcp-server.js";
startMcp(process.argv.slice(2)).catch((error) => {
  console.error("kaiprint-mcp: " + error.message);
  process.exitCode = 1;
});
