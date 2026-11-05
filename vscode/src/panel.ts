import * as crypto from 'crypto';
import * as vscode from 'vscode';

export interface PanelActions {
    generate(includeObjects: boolean): Promise<{ result: any; planFile: string }>;
    apply(planFile: string): Promise<any>;
}

/**
 * The 4-agent wizard as a webview: Donatello, Leonardo, Michelangelo and Raphael tabs, with
 * "Generate Modern Artifacts" and "Apply to Workspace", like the Eclipse and IntelliJ dialogs.
 */
export class MigrationPanel {

    static show(extensionUri: vscode.Uri, analysis: any, actions: PanelActions): void {
        const panel = vscode.window.createWebviewPanel(
            'osgiAiMigrator', `OSGi AI Migrator: ${analysis.moduleName}`, vscode.ViewColumn.Active,
            { enableScripts: true, retainContextWhenHidden: true, localResourceRoots: [] });
        new MigrationPanel(panel, analysis, actions);
    }

    private planFile: string | undefined;

    private constructor(private readonly panel: vscode.WebviewPanel, analysis: any, private readonly actions: PanelActions) {
        panel.webview.html = html(panel.webview, analysis.report);
        panel.webview.onDidReceiveMessage((message) => this.onMessage(message));
    }

    private async onMessage(message: any): Promise<void> {
        if (message.type === 'generate') {
            await vscode.window.withProgress({
                location: vscode.ProgressLocation.Notification,
                title: 'Leonardo and Michelangelo are working with the AI provider, this can take a few minutes...',
            }, async () => {
                try {
                    const { result, planFile } = await this.actions.generate(message.includeObjects);
                    this.planFile = planFile;
                    this.post({
                        type: 'generated',
                        blueprint: result.blueprint || 'No blueprint generated.',
                        files: filesText(result.files),
                        verification: result.verificationReport,
                        canApply: result.files.length > 0,
                    });
                } catch (e: any) {
                    this.post({ type: 'failed' });
                    vscode.window.showErrorMessage(`Migration pipeline error: ${e.message}`);
                }
            });
        } else if (message.type === 'apply' && this.planFile) {
            try {
                const written = (await this.actions.apply(this.planFile)).written;
                const lines = [`Written to ${written.workspace}`, `Migration report: ${written.report}`]
                    .concat(written.clientExtensions.map((d: string) => `client-extensions/${d}/`))
                    .concat(written.fragmentCollections.map((c: string) => `fragments/${c}.zip`));
                vscode.window.showInformationMessage(`Raphael (Verifier): modern artifacts written. ${lines.join(' · ')}`, { modal: true });
            } catch (e: any) {
                vscode.window.showErrorMessage(`Failed to write files: ${e.message}`);
            }
        }
    }

    private post(message: any): void {
        this.panel.webview.postMessage(message);
    }
}

function filesText(files: { path: string; content: string }[]): string {
    if (!files.length) {
        return 'No files generated.';
    }
    const line = '='.repeat(40);
    return files.map((f) => `${line}\nFILE: ${f.path}\n${line}\n${f.content}\n`).join('\n');
}

function html(webview: vscode.Webview, donatelloReport: string): string {
    const nonce = crypto.randomBytes(16).toString('base64');
    return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src ${webview.cspSource} 'unsafe-inline'; script-src 'nonce-${nonce}';">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
  body { font-family: var(--vscode-font-family); color: var(--vscode-foreground); padding: 0 16px 16px; }
  h2 { font-weight: 600; margin: 16px 0 4px; }
  .muted { color: var(--vscode-descriptionForeground); margin: 0 0 12px; }
  .bar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
  button { background: var(--vscode-button-background); color: var(--vscode-button-foreground); border: none; padding: 6px 14px; cursor: pointer; }
  button:hover:not(:disabled) { background: var(--vscode-button-hoverBackground); }
  button.secondary { background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); }
  button:disabled { opacity: 0.5; cursor: default; }
  .tabs { display: flex; border-bottom: 1px solid var(--vscode-panel-border); }
  .tab { background: none; color: var(--vscode-foreground); padding: 8px 12px; border-bottom: 2px solid transparent; }
  .tab[aria-selected="true"] { border-bottom-color: var(--vscode-focusBorder); font-weight: 600; }
  pre { font-family: var(--vscode-editor-font-family); font-size: var(--vscode-editor-font-size); white-space: pre-wrap;
        background: var(--vscode-textCodeBlock-background); padding: 12px; margin: 0; min-height: 300px; overflow: auto; }
  [hidden] { display: none; }
</style>
</head>
<body>
<h2>OSGi AI Migrator for Liferay (4-Agent Pipeline)</h2>
<p class="muted">Inspect, architect, generate, and verify modern Liferay Objects and Client Extensions.</p>
<div class="bar">
  <label><input type="checkbox" id="objects" checked> Also generate Liferay Objects (Batch Client Extension) for the entities used</label>
</div>
<div class="bar">
  <button id="generate">Generate Modern Artifacts</button>
  <button id="apply" class="secondary" disabled>Apply to Workspace</button>
</div>
<div class="tabs" role="tablist">
  <button class="tab" role="tab" data-tab="0" aria-selected="true">1. Donatello (Analyzer)</button>
  <button class="tab" role="tab" data-tab="1" aria-selected="false">2. Leonardo (Architect)</button>
  <button class="tab" role="tab" data-tab="2" aria-selected="false">3. Michelangelo (Coder)</button>
  <button class="tab" role="tab" data-tab="3" aria-selected="false">4. Raphael (Verifier)</button>
</div>
<pre id="panel0"></pre>
<pre id="panel1" hidden>Click 'Generate Modern Artifacts' to let Leonardo design the target architecture.</pre>
<pre id="panel2" hidden>Generated files will appear here.</pre>
<pre id="panel3" hidden>Pre-flight verification will appear here after generation.</pre>
<script nonce="${nonce}">
  const vscode = acquireVsCodeApi();
  const generate = document.getElementById('generate');
  const apply = document.getElementById('apply');
  document.getElementById('panel0').textContent = ${JSON.stringify(donatelloReport).replace(/</g, '\\u003c')};

  function select(index) {
    document.querySelectorAll('.tab').forEach((t) => t.setAttribute('aria-selected', String(t.dataset.tab === String(index))));
    for (let i = 0; i < 4; i++) document.getElementById('panel' + i).hidden = i !== index;
  }
  document.querySelectorAll('.tab').forEach((t) => t.addEventListener('click', () => select(Number(t.dataset.tab))));

  generate.addEventListener('click', () => {
    generate.disabled = true;
    generate.textContent = 'Generating Modern Artifacts...';
    vscode.postMessage({ type: 'generate', includeObjects: document.getElementById('objects').checked });
  });
  apply.addEventListener('click', () => vscode.postMessage({ type: 'apply' }));

  window.addEventListener('message', (event) => {
    const m = event.data;
    generate.disabled = false;
    generate.textContent = 'Generate Modern Artifacts';
    if (m.type === 'generated') {
      document.getElementById('panel1').textContent = m.blueprint;
      document.getElementById('panel2').textContent = m.files;
      document.getElementById('panel3').textContent = m.verification;
      apply.disabled = !m.canApply;
      select(2);
    }
  });
</script>
</body>
</html>`;
}
