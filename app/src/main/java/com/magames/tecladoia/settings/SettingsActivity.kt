package com.magames.tecladoia.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.magames.tecladoia.Prefs
import com.magames.tecladoia.spell.SpellEngine
import com.magames.tecladoia.voice.VoskModelManager

/** Setup + settings screen (also the launcher activity). */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var stepEnable: TextView
    private lateinit var stepSelect: TextView
    private lateinit var micStatus: TextView
    private lateinit var modelStatus: TextView
    private lateinit var modelProgress: ProgressBar
    private lateinit var modelButton: Button
    private lateinit var modelDelete: Button
    private lateinit var dictStatus: TextView

    private val modelListener: (VoskModelManager.State) -> Unit = { renderModel(it) }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = "Teclado IA"

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(16f), dp(20f), dp(32f))
        }
        setContentView(ScrollView(this).apply { addView(col) })

        col.addView(text("Teclado IA", 26f, bold = true, color = Color.parseColor("#7C3AED")))
        col.addView(text("Teclado grátis com voz em tempo real e correção em português. Funciona online e offline. Sem anúncios, sem cobrança.", 14f))

        // 1. Enable
        col.addView(header("1. Ativar o teclado"))
        stepEnable = text("", 14f)
        col.addView(stepEnable)
        col.addView(button("Abrir Idiomas e entrada") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        stepSelect = text("", 14f)
        col.addView(stepSelect)
        col.addView(button("Escolher Teclado IA como teclado atual") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })

        // 2. Mic
        col.addView(header("2. Microfone (ditado por voz)"))
        micStatus = text("", 14f)
        col.addView(micStatus)
        col.addView(button("Permitir microfone") {
            if (Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        })

        // 3. Offline model
        col.addView(header("3. Voz offline (sem internet)"))
        col.addView(text("Baixe uma vez o modelo de voz em português (cerca de 31 MB, gratuito, Vosk). Ele fica guardado no celular e não aumenta o app.", 14f))
        modelStatus = text("", 14f)
        col.addView(modelStatus)
        modelProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        col.addView(modelProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24f)))
        modelButton = button("Baixar modelo offline") { onModelButton() }
        col.addView(modelButton)
        modelDelete = button("Apagar modelo") { VoskModelManager.delete(this) }
        col.addView(modelDelete)

        // 4. Options
        col.addView(header("4. Opções"))
        col.addView(switch("Somente offline (nunca usar internet)", prefs.offlineOnly) { prefs.offlineOnly = it })
        col.addView(switch("Autocorreção ao apertar espaço", prefs.autocorrect) { prefs.autocorrect = it })
        col.addView(switch("Correção online de gramática (LanguageTool)", prefs.onlineCheck) { prefs.onlineCheck = it })
        col.addView(switch("Vibrar ao tocar", prefs.vibrate) { prefs.vibrate = it })
        dictStatus = text("", 13f, color = Color.GRAY)
        col.addView(dictStatus)

        // 5. Test
        col.addView(header("5. Teste aqui"))
        col.addView(EditText(this).apply {
            hint = "Digite ou toque no 🎤 para falar…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
        })

        col.addView(header("Como funciona"))
        col.addView(text(
            "• Voz: com internet usa o reconhecimento de voz do Android (online, grátis); sem internet usa o Vosk no próprio celular.\n" +
                "• Correção: offline com SymSpell + dicionário de 50 mil palavras; online também verifica gramática com o LanguageTool (pausa de ~0,8 s, respeitando o limite gratuito).\n" +
                "• Segure uma tecla para acentos (á ã â, é ê, í, ó õ ô, ú, ç) e números. Segure o espaço para trocar de teclado.\n" +
                "• Duas vezes espaço = ponto final. Backspace logo após uma autocorreção desfaz.", 14f))

        col.addView(header("Licenças"))
        col.addView(text(
            "Dicionário: FrequencyWords pt_br 50k por Hermit Dave (código MIT; conteúdo CC BY-SA 4.0, derivado do OpenSubtitles).\n" +
                "SymSpell: algoritmo de Wolf Garbe (MIT), portado para Kotlin.\n" +
                "Vosk e modelo vosk-model-small-pt-0.3: Alpha Cephei, Apache 2.0.\n" +
                "LanguageTool: API pública gratuita (languagetool.org), texto enviado apenas quando a correção online está ligada.",
            12f, color = Color.GRAY))
    }

    override fun onResume() {
        super.onResume()
        VoskModelManager.addListener(modelListener)
        renderModel(VoskModelManager.state(this))
        renderSetup()
    }

    override fun onPause() {
        VoskModelManager.removeListener(modelListener)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) renderSetup()  // after the IME picker closes
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderSetup()
    }

    private fun renderSetup() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        val selected = current.startsWith("$packageName/")
        stepEnable.text = if (enabled) "✅ Teclado IA está ativado." else
            "❌ Ative \"Teclado IA\" em Configurações > Sistema > Idiomas e entrada > Teclado na tela > Gerenciar teclados."
        stepSelect.text = if (selected) "✅ Teclado IA é o teclado atual." else "Depois, escolha o Teclado IA como teclado atual."
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micStatus.text = if (mic) "✅ Microfone permitido." else "❌ Microfone ainda não permitido."
        dictStatus.text = if (SpellEngine.ready) "Dicionário offline pronto." else "Carregando dicionário offline…"
        if (!SpellEngine.ready) SpellEngine.init(this) { runOnUiThread { dictStatus.text = "Dicionário offline pronto." } }
    }

    private fun onModelButton() {
        when (VoskModelManager.state(this)) {
            is VoskModelManager.State.Downloading -> VoskModelManager.cancel()
            is VoskModelManager.State.Unpacking -> {}
            else -> VoskModelManager.download(this)
        }
    }

    private fun renderModel(s: VoskModelManager.State) {
        when (s) {
            is VoskModelManager.State.NotInstalled -> {
                modelStatus.text = "❌ Modelo offline não baixado."
                modelProgress.visibility = View.GONE
                modelButton.text = "Baixar modelo offline (~31 MB)"
                modelButton.isEnabled = true
                modelDelete.visibility = View.GONE
            }
            is VoskModelManager.State.Downloading -> {
                val mb = s.bytes / (1024 * 1024)
                val totalMb = if (s.total > 0) s.total / (1024 * 1024) else 0
                modelStatus.text = "Baixando… ${s.percent}% ($mb de $totalMb MB)"
                modelProgress.visibility = View.VISIBLE
                modelProgress.isIndeterminate = s.total <= 0
                modelProgress.progress = s.percent
                modelButton.text = "Cancelar"
                modelButton.isEnabled = true
                modelDelete.visibility = View.GONE
            }
            is VoskModelManager.State.Unpacking -> {
                modelStatus.text = "Instalando modelo…"
                modelProgress.visibility = View.VISIBLE
                modelProgress.isIndeterminate = true
                modelButton.isEnabled = false
            }
            is VoskModelManager.State.Installed -> {
                modelStatus.text = "✅ Modelo offline instalado. A voz funciona sem internet."
                modelProgress.visibility = View.GONE
                modelButton.text = "Baixar de novo"
                modelButton.isEnabled = true
                modelDelete.visibility = View.VISIBLE
            }
            is VoskModelManager.State.Failed -> {
                modelStatus.text = "⚠️ Falhou: ${s.message}. Verifique a internet e tente de novo."
                modelProgress.visibility = View.GONE
                modelButton.text = "Tentar de novo"
                modelButton.isEnabled = true
                modelDelete.visibility = View.GONE
            }
        }
    }

    // ---------- tiny view helpers ----------

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.parseColor("#222222")) =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4f), 0, dp(4f))
        }

    private fun header(s: String) = text(s, 18f, bold = true).apply { setPadding(0, dp(20f), 0, dp(4f)) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    @Suppress("DEPRECATION")
    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        isChecked = checked
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(0, dp(8f), 0, dp(8f))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
}
