import { fileURLToPath } from 'node:url';
import { config } from 'dotenv';

// Both src/ and dist/ are one level below the package root.
config({ path: fileURLToPath(new URL('../../../.env', import.meta.url)), quiet: true });
