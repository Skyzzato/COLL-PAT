Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()
$configPath = Join-Path (Split-Path $PSScriptRoot -Parent) '.env'
$form = New-Object System.Windows.Forms.Form
$form.Text = 'Collettori - collegamento Supabase'
$form.ClientSize = New-Object System.Drawing.Size(570, 330)
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
$form.TopMost = $true
$intro = New-Object System.Windows.Forms.Label
$intro.Text = 'Inserisci la password del ruolo collettori_backend impostata nel SQL Editor. Non usare la publishable key.'
$intro.SetBounds(20, 20, 530, 45)
$form.Controls.Add($intro)
$label1 = New-Object System.Windows.Forms.Label
$label1.Text = 'Password database'
$label1.SetBounds(20, 78, 250, 22)
$form.Controls.Add($label1)
$passwordBox = New-Object System.Windows.Forms.TextBox
$passwordBox.UseSystemPasswordChar = $true
$passwordBox.SetBounds(20, 102, 530, 26)
$form.Controls.Add($passwordBox)
$label2 = New-Object System.Windows.Forms.Label
$label2.Text = 'Ripeti la stessa password'
$label2.SetBounds(20, 140, 300, 22)
$form.Controls.Add($label2)
$confirmBox = New-Object System.Windows.Forms.TextBox
$confirmBox.UseSystemPasswordChar = $true
$confirmBox.SetBounds(20, 164, 530, 26)
$form.Controls.Add($confirmBox)
$statusLabel = New-Object System.Windows.Forms.Label
$statusLabel.Text = 'Compariranno degli asterischi mentre digiti. Puoi anche incollare con Ctrl+V.'
$statusLabel.SetBounds(20, 207, 530, 45)
$form.Controls.Add($statusLabel)
$saveButton = New-Object System.Windows.Forms.Button
$saveButton.Text = 'Salva configurazione'
$saveButton.SetBounds(310, 272, 240, 36)
$form.Controls.Add($saveButton)
$form.AcceptButton = $saveButton
$saveButton.Add_Click({
    if (Test-Path -LiteralPath $configPath) {
        $statusLabel.Text = 'Configurazione gia presente: nessun file sovrascritto. Avvisa Codex.'
        return
    }
    if ($passwordBox.Text.Length -lt 20) {
        $statusLabel.Text = 'La procedura richiede la password casuale di almeno 20 caratteri impostata su Supabase.'
        return
    }
    if ($passwordBox.Text -cne $confirmBox.Text) {
        $statusLabel.Text = 'Le due password non coincidono. Riprova.'
        return
    }
    try {
        $encodedPassword = [Uri]::EscapeDataString($passwordBox.Text)
        $databaseUrl = 'postgresql+psycopg://collettori_backend.zzipvrnhndigepufhkcj:' + $encodedPassword + '@aws-1-eu-west-1.pooler.supabase.com:5432/postgres?sslmode=require&connect_timeout=10'
        $content = "DATABASE_URL=$databaseUrl`nDB_SCHEMA=collettori`nOFFLINE_HOURS=72`n"
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($content)
        $stream = [System.IO.File]::Open($configPath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try { $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
        $passwordBox.Clear()
        $confirmBox.Clear()
        $encodedPassword = $null
        $databaseUrl = $null
        $content = $null
        [Array]::Clear($bytes, 0, $bytes.Length)
        $statusLabel.Text = 'Configurazione salvata. Puoi chiudere e scrivere FATTO a Codex.'
        $saveButton.Enabled = $false
    } catch {
        $statusLabel.Text = 'Salvataggio non riuscito. Avvisa Codex senza comunicare la password.'
    }
})
$form.Add_Shown({ $form.Activate(); $passwordBox.Focus() })
[void]$form.ShowDialog()
$form.Dispose()
