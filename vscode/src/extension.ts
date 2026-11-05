import * as path from 'path';
import * as vscode from 'vscode';
import { buildArgs, CliResult, errorMessage, findJava, MigratorSettings, parseJavaMajor, run } from './cli';
import { MigrationPanel } from './panel';

/** Secret storage id and environment variable of each provider's API key. */
const API_KEYS: { [provider: string]: { secret: string; env: string; label: string } } = {
    GEMINI: { secret: 'osgiAiMigrator.geminiApiKey', env: 'GEMINI_API_KEY', label: 'Google Gemini (AI Studio)' },
    ANTHROPIC: { secret: 'osgiAiMigrator.anthropicApiKey', env: 'ANTHROPIC_API_KEY', label: 'Anthropic Claude' },
    OPENAI: { secret: 'osgiAiMigrator.openaiApiKey', env: 'OPENAI_API_KEY', label: 'OpenAI-compatible' },
};

export function activate(context: vscode.ExtensionContext): void {
    const migrator = new Migrator(context);
    context.subscriptions.push(
        vscode.commands.registerCommand('osgiAiMigrator.migrate', (uri?: vscode.Uri) => migrator.migrate(uri)),
        vscode.commands.registerCommand('osgiAiMigrator.setApiKey', () => migrator.setApiKey()),
    );
}

export function deactivate(): void {}

/** Runs the bundled CLI for the panel: analyze, then migrate (saving the plan), then apply that plan. */
export class Migrator {

    constructor(private readonly context: vscode.ExtensionContext) {}

    async migrate(uri?: vscode.Uri): Promise<void> {
        if (!uri || uri.scheme !== 'file') {
            vscode.window.showErrorMessage('Right-click a module folder in the Explorer to migrate it.');
            return;
        }
        const java = await this.checkJava();
        if (!java) {
            return;
        }
        const moduleDir = uri.fsPath;
        const result = await vscode.window.withProgress(
            { location: vscode.ProgressLocation.Notification, title: `Donatello: analyzing ${path.basename(moduleDir)}` },
            () => this.cli(java, 'analyze', moduleDir));
        if (result.code !== 0) {
            vscode.window.showErrorMessage(`Failed to analyze the module: ${errorMessage(result)}`);
            return;
        }
        MigrationPanel.show(this.context.extensionUri, JSON.parse(result.stdout), {
            generate: (includeObjects) => this.generate(java, moduleDir, includeObjects),
            apply: (planFile) => this.apply(java, moduleDir, planFile),
        });
    }

    async setApiKey(): Promise<void> {
        const current = vscode.workspace.getConfiguration('osgiAiMigrator').get<string>('provider', 'GEMINI');
        const items = Object.keys(API_KEYS).map((id) => ({ label: API_KEYS[id].label, id, picked: id === current }));
        items.sort((a, b) => Number(b.picked) - Number(a.picked));
        const pick = await vscode.window.showQuickPick(items, { title: 'API key of which AI provider?' });
        if (!pick) {
            return;
        }
        const target = API_KEYS[pick.id];
        const key = await vscode.window.showInputBox({
            title: `${target.label} API key`,
            prompt: 'Stored in VS Code secret storage. Leave empty to remove it.',
            password: true,
            ignoreFocusOut: true,
        });
        if (key === undefined) {
            return;
        }
        if (key.trim()) {
            await this.context.secrets.store(target.secret, key.trim());
            const hint = pick.id === 'GEMINI' ? ' Set "OSGi AI Migrator: Auth Type" to AI_STUDIO to use it.'
                : pick.id !== current ? ` Set "OSGi AI Migrator: Provider" to ${pick.id} to use it.` : '';
            vscode.window.showInformationMessage(`${target.label} API key saved.${hint}`);
        } else {
            await this.context.secrets.delete(target.secret);
            vscode.window.showInformationMessage(`${target.label} API key removed.`);
        }
    }

    private async generate(java: string, moduleDir: string, includeObjects: boolean): Promise<{ result: any; planFile: string }> {
        const plans = vscode.Uri.joinPath(this.context.globalStorageUri, 'plans');
        await vscode.workspace.fs.createDirectory(plans);
        const planFile = path.join(plans.fsPath, `${path.basename(moduleDir)}-${Date.now()}.txt`);
        const extra = ['--save-plan', planFile].concat(includeObjects ? [] : ['--no-backend']);
        const result = await this.cli(java, 'migrate', moduleDir, extra);
        if (result.code !== 0) {
            throw new Error(errorMessage(result));
        }
        return { result: JSON.parse(result.stdout), planFile };
    }

    private async apply(java: string, moduleDir: string, planFile: string): Promise<any> {
        const result = await this.cli(java, 'apply', moduleDir, ['--plan', planFile]);
        if (result.code !== 0) {
            throw new Error(errorMessage(result));
        }
        return JSON.parse(result.stdout);
    }

    private async cli(java: string, command: string, moduleDir: string, extra: string[] = []): Promise<CliResult> {
        const jar = vscode.Uri.joinPath(this.context.extensionUri, 'cli', 'osgi-ai-migrator-cli.jar').fsPath;
        // A config file that does not exist: only VS Code's settings (and the environment) apply
        const configFile = vscode.Uri.joinPath(this.context.globalStorageUri, 'no-config.properties').fsPath;
        const env = { ...process.env };
        for (const id of Object.keys(API_KEYS)) {
            const apiKey = await this.context.secrets.get(API_KEYS[id].secret);
            if (apiKey) {
                env[API_KEYS[id].env] = apiKey;
            }
        }
        return run(java, buildArgs(jar, command, moduleDir, readSettings(), configFile, extra), env);
    }

    /** The java to use, or undefined after telling the user how to fix it. */
    private async checkJava(): Promise<string | undefined> {
        const javaHome = vscode.workspace.getConfiguration('osgiAiMigrator').get<string>('javaHome', '');
        const java = findJava(javaHome, process.env);
        const version = await run(java, ['-version'], process.env);
        const major = parseJavaMajor(version.stderr + version.stdout);
        if (version.code === 0 && major !== undefined && major >= 17) {
            return java;
        }
        const found = major !== undefined ? `Java ${major} was found (${java})` : `Java was not found (${java})`;
        const choice = await vscode.window.showErrorMessage(
            `OSGi AI Migrator needs Java 17 or later. ${found}. Set "OSGi AI Migrator: Java Home" to a JDK 17+ folder.`,
            'Open Settings');
        if (choice) {
            vscode.commands.executeCommand('workbench.action.openSettings', 'osgiAiMigrator.javaHome');
        }
        return undefined;
    }
}

function readSettings(): MigratorSettings {
    const config = vscode.workspace.getConfiguration('osgiAiMigrator');
    const provider = config.get<string>('provider', 'GEMINI');
    const model = provider === 'ANTHROPIC' ? config.get<string>('anthropicModel', '')
        : provider === 'OPENAI' ? config.get<string>('openaiModel', '') : config.get<string>('model', '');
    const endpoint = provider === 'ANTHROPIC' ? config.get<string>('anthropicEndpoint', '')
        : provider === 'OPENAI' ? config.get<string>('openaiBaseUrl', '') : config.get<string>('endpoint', '');
    return {
        provider,
        effort: config.get<string>('anthropicEffort', ''),
        authType: config.get<string>('authType', 'GCLOUD_ADC'),
        projectId: config.get<string>('projectId', ''),
        region: config.get<string>('region', ''),
        serviceAccountFile: config.get<string>('serviceAccountFile', ''),
        model,
        temperature: config.get<number>('temperature', 0.2),
        endpoint,
    };
}
