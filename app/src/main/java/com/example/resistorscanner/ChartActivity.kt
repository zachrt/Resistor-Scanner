package com.example.resistorscanner
import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.widget.TableRow
import androidx.appcompat.app.AppCompatActivity

class ChartActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chart)

        supportActionBar?.apply {
            title = "Resistor Reference"
            setDisplayHomeAsUpEnabled(true)
        }

        // Get the colors passed from MainActivity
        val colors = listOf(
            intent.getStringExtra("BAND1"),
            intent.getStringExtra("BAND2"),
            intent.getStringExtra("BAND3"),
            intent.getStringExtra("BAND4")
        )

        // Highlight every row that was part of the scan
        colors.forEach { colorName ->
            if (colorName != null && colorName != "BODY") {
                highlightRow(colorName)
            }
        }
    }

    private fun highlightRow(colorName: String) {
        // Map the Enum Name to the XML ID
        val rowId = when (colorName) {
            "BLACK" -> R.id.rowBlack
            "BROWN" -> R.id.rowBrown
            "RED" -> R.id.rowRed
            "ORANGE" -> R.id.rowOrange
            "YELLOW" -> R.id.rowYellow
            "GREEN" -> R.id.rowGreen
            "BLUE" -> R.id.rowBlue
            "VIOLET" -> R.id.rowViolet
            "GOLD" -> R.id.rowGold
            "SILVER" -> R.id.rowSilver
            else -> null
        }

        rowId?.let {
            findViewById<TableRow>(it).setBackgroundColor(Color.parseColor("#4DFFEB3B")) // 30% Transparent Yellow
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
