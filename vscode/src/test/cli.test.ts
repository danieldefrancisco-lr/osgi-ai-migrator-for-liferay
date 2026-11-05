import { strict as assert } from 'assert';
import { test } from 'node:test';
import { buildArgs, errorMessage, parseJavaMajor } from '../cli';

const settings = {
    authType: 'GCLOUD_ADC',
    projectId: 'my-project',
    region: '',
    serviceAccountFile: '',
    model: 'gemini-2.5-flash',
    temperature: 0.2,
    endpoint: '',
};

test('parses Java major versions', () => {
    assert.equal(parseJavaMajor('java version "1.8.0_421"'), 8);
    assert.equal(parseJavaMajor('openjdk version "17.0.12" 2024-07-16'), 17);
    assert.equal(parseJavaMajor('java version "21.0.6" 2025-01-21 LTS'), 21);
    assert.equal(parseJavaMajor('openjdk version "22" 2024-03-19'), 22);
    assert.equal(parseJavaMajor('nonsense'), undefined);
});

test('passes only the settings that are set, and isolates the config file', () => {
    const args = buildArgs('cli.jar', 'migrate', '/ws/modules/m', settings, '/storage/none.properties', ['--no-backend']);
    assert.deepEqual(args, [
        '-jar', 'cli.jar', 'migrate', '/ws/modules/m', '--json', '--config', '/storage/none.properties',
        '--auth', 'GCLOUD_ADC', '--project', 'my-project', '--model', 'gemini-2.5-flash', '--temperature', '0.2',
        '--no-backend',
    ]);
});

test('passes the provider, and the Google options only to Gemini', () => {
    const claude = buildArgs('cli.jar', 'migrate', '/m', { ...settings, provider: 'ANTHROPIC', model: 'claude-opus-5-5', effort: 'medium' },
        '/none.properties');
    assert.deepEqual(claude, [
        '-jar', 'cli.jar', 'migrate', '/m', '--json', '--config', '/none.properties',
        '--provider', 'ANTHROPIC', '--model', 'claude-opus-5-5', '--effort', 'medium',
    ]);
    const local = buildArgs('cli.jar', 'migrate', '/m', { ...settings, provider: 'OPENAI', model: 'qwen3-coder',
        endpoint: 'http://localhost:11434/v1', effort: 'high' }, '/none.properties');
    assert.deepEqual(local.slice(7), ['--provider', 'OPENAI', '--model', 'qwen3-coder', '--endpoint', 'http://localhost:11434/v1']);
});

test('extracts the CLI error lines', () => {
    const result = {
        code: 1,
        stdout: '',
        stderr: 'Donatello: analyzing x ...\nError: [Phase 1]: Gemini Gateway Error (HTTP 403)\nCaused by: RuntimeException - blocked\n',
    };
    assert.equal(errorMessage(result), 'Error: [Phase 1]: Gemini Gateway Error (HTTP 403)\nCaused by: RuntimeException - blocked');
});
