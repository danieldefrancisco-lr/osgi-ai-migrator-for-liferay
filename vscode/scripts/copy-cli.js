// Copies the CLI jar built by Maven (and the license) into the extension before packaging
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..', '..');
const jar = path.join(root, 'cli', 'target', 'osgi-ai-migrator-cli.jar');
if (!fs.existsSync(jar)) {
    console.error(`Missing ${jar}: run "./mvnw install" at the repository root first.`);
    process.exit(1);
}
fs.mkdirSync(path.join(__dirname, '..', 'cli'), { recursive: true });
fs.copyFileSync(jar, path.join(__dirname, '..', 'cli', 'osgi-ai-migrator-cli.jar'));
fs.copyFileSync(path.join(root, 'LICENSE'), path.join(__dirname, '..', 'LICENSE'));
console.log('Copied the CLI jar and LICENSE');
