param([string]$Username = 'angelo.spadaro')
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()
$projectRoot = Split-Path $PSScriptRoot -Parent
$form = New-Object System.Windows.Forms.Form
$form.Text = 'Collettori - crea amministratore'
$form.ClientSize = New-Object System.Drawing.Size(580, 340)
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
$form.TopMost = $true
$intro = New-Object System.Windows.Forms.Label
$intro.Text = "Account: $Username`nScegli una password dell'app (almeno 12 caratteri), diversa da quella del database. Ambito iniziale: DEMO sintetico."
$intro.SetBounds(20, 15, 540, 65)
$form.Controls.Add($intro)
$label1 = New-Object System.Windows.Forms.Label
$label1.Text = 'Nuova password dell''app'
$label1.SetBounds(20, 85, 300, 22)
$form.Controls.Add($label1)
$passwordBox = New-Object System.Windows.Forms.TextBox
$passwordBox.UseSystemPasswordChar = $true
$passwordBox.SetBounds(20, 110, 540, 26)
$form.Controls.Add($passwordBox)
$label2 = New-Object System.Windows.Forms.Label
$label2.Text = 'Ripeti la password'
$label2.SetBounds(20, 145, 300, 22)
$form.Controls.Add($label2)
$confirmBox = New-Object System.Windows.Forms.TextBox
$confirmBox.UseSystemPasswordChar = $true
$confirmBox.SetBounds(20, 170, 540, 26)
$form.Controls.Add($confirmBox)
$statusLabel = New-Object System.Windows.Forms.Label
$statusLabel.Text = 'La password non viene salvata in file o inviata alla chat.'
$statusLabel.SetBounds(20, 212, 540, 50)
$form.Controls.Add($statusLabel)
$saveButton = New-Object System.Windows.Forms.Button
$saveButton.Text = 'Crea amministratore'
$saveButton.SetBounds(320, 280, 240, 36)
$form.Controls.Add($saveButton)
$form.AcceptButton = $saveButton
$saveButton.Add_Click({
    if ($passwordBox.Text.Length -lt 12) { $statusLabel.Text = 'Servono almeno 12 caratteri.'; return }
    if ($passwordBox.Text -cne $confirmBox.Text) { $statusLabel.Text = 'Le password non coincidono.'; return }
    $saveButton.Enabled = $false
    $statusLabel.Text = 'Creazione in corso su Supabase...'
    $form.Refresh()
    try {
        $startInfo = New-Object System.Diagnostics.ProcessStartInfo
        $startInfo.FileName = Join-Path $projectRoot '.venv\Scripts\python.exe'
        $startInfo.Arguments = '"' + (Join-Path $PSScriptRoot 'bootstrap_local_account.py') + '"'
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardInput = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $startInfo.EnvironmentVariables['PYTHONIOENCODING'] = 'utf-8'
        $child = [System.Diagnostics.Process]::Start($startInfo)
        $payload = @{ username = $Username; password = $passwordBox.Text } | ConvertTo-Json -Compress
        $payloadBytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
        $child.StandardInput.BaseStream.Write($payloadBytes, 0, $payloadBytes.Length)
        $child.StandardInput.BaseStream.Flush()
        $child.StandardInput.Close()
        [Array]::Clear($payloadBytes, 0, $payloadBytes.Length)
        $payload = $null
        $stdoutTask = $child.StandardOutput.ReadToEndAsync()
        $stderrTask = $child.StandardError.ReadToEndAsync()
        if (-not $child.WaitForExit(30000)) {
            $statusLabel.Text = 'Operazione ancora in corso. Avvisa Codex prima di riprovare.'
            return
        }
        if ($child.ExitCode -ne 0) { throw 'Creazione fallita' }
        $passwordBox.Clear(); $confirmBox.Clear()
        $statusLabel.Text = 'Amministratore creato. Ora puoi accedere al portale e scrivere FATTO a Codex.'
        $child.Dispose()
    } catch {
        $statusLabel.Text = 'Creazione non riuscita. Avvisa Codex senza comunicare la password.'
        $saveButton.Enabled = $true
    }
})
$form.Add_Shown({ $form.Activate(); $passwordBox.Focus() })
[void]$form.ShowDialog()
$form.Dispose()
