// Runs the bundled osgi-ai-migrator CLI jar. No VS Code APIs here, so it can be unit tested with plain Node.
import { execFile } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';

export interface MigratorSettings {
    /** GEMINI, ANTHROPIC or OPENAI; empty means GEMINI. */
    provider?: string;
    /** Claude only. */
    effort?: string;
    authType: string;
    projectId: string;
    region: string;
    serviceAccountFile: string;
    model: string;
    temperature: number;
    endpoint: string;
}

export interface CliResult {
    code: number;
    stdout: string;
    stderr: string;
}

/** The java executable: the configured JDK, then JAVA_HOME, then the PATH. */
export function findJava(javaHome: string, env: NodeJS.ProcessEnv, platform: string = process.platform): string {
    const exe = platform === 'win32' ? 'java.exe' : 'java';
    for (const home of [javaHome, env.JAVA_HOME]) {
        if (home && home.trim()) {
            const candidate = path.join(home.trim(), 'bin', exe);
            if (fs.existsSync(candidate)) {
                return candidate;
            }
        }
    }
    return exe;
}

/** Major version from "java -version" output: 1.8.0_x -> 8, 21.0.6 -> 21. */
export function parseJavaMajor(versionOutput: string): number | undefined {
    const match = /version "(\d+)(?:\.(\d+))?/.exec(versionOutput);
    if (!match) {
        return undefined;
    }
    const first = Number(match[1]);
    return first === 1 && match[2] ? Number(match[2]) : first;
}

/**
 * CLI arguments for one command. Settings go as options and the config file points at a file that does not
 * exist, so VS Code's settings are the only ones that apply (plus GEMINI_API_KEY in the environment).
 */
export function buildArgs(jar: string, command: string, moduleDir: string, settings: MigratorSettings,
                          configFile: string, extra: string[] = []): string[] {
    const args = ['-jar', jar, command, moduleDir, '--json', '--config', configFile];
    const provider = (settings.provider || 'GEMINI').toUpperCase();
    // model and endpoint are those of the selected provider; the Google options only apply to Gemini
    const options: [string, string | undefined][] = provider === 'GEMINI' ? [
        ['--auth', settings.authType],
        ['--project', settings.projectId],
        ['--region', settings.region],
        ['--service-account', settings.serviceAccountFile],
        ['--model', settings.model],
        ['--temperature', settings.temperature === undefined ? '' : String(settings.temperature)],
        ['--endpoint', settings.endpoint],
    ] : [
        ['--provider', provider],
        ['--model', settings.model],
        ['--endpoint', settings.endpoint],
        ['--effort', provider === 'ANTHROPIC' ? settings.effort : ''],
    ];
    for (const [option, value] of options) {
        if (value && value.trim()) {
            args.push(option, value.trim());
        }
    }
    return args.concat(extra);
}

export function run(java: string, args: string[], env: NodeJS.ProcessEnv): Promise<CliResult> {
    return new Promise((resolve) => {
        execFile(java, args, { env, maxBuffer: 64 * 1024 * 1024, windowsHide: true }, (error, stdout, stderr) => {
            const code = error ? (typeof error.code === 'number' ? error.code : 1) : 0;
            resolve({ code, stdout: stdout.toString(), stderr: stderr.toString() || (error && code !== 0 ? error.message : '') });
        });
    });
}

/** The CLI's "Error: ..." lines, or the end of stderr. */
export function errorMessage(result: CliResult): string {
    const lines = result.stderr.split(/\r?\n/).filter((l) => l.startsWith('Error:') || l.startsWith('Caused by:'));
    return lines.length ? lines.join('\n') : result.stderr.trim().split(/\r?\n/).slice(-5).join('\n');
}
