package com.example.pdfview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import com.wdeo3601.pdfview.PDFView

class PdfDetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_IS_LOCAL_FILE = "extra_is_local_file"
        private const val EXTRA_FILE_PATH = "extra_file_path"
        private const val EXTRA_FILE_URL = "extra_file_url"

        fun startMeWithFilePath(context: Context, filePath: String) {
            val intent = Intent(context, PdfDetailActivity::class.java)
            intent.putExtras(
                bundleOf(
                    EXTRA_IS_LOCAL_FILE to true,
                    EXTRA_FILE_PATH to filePath
                )
            )
            context.startActivity(intent)
        }

        fun startMeWithFileUrl(context: Context, pdfUrl: String) {
            val intent = Intent(context, PdfDetailActivity::class.java)
            intent.putExtras(
                bundleOf(
                    EXTRA_IS_LOCAL_FILE to false,
                    EXTRA_FILE_URL to pdfUrl
                )
            )
            context.startActivity(intent)
        }
    }

    private val mIsLocalFile by lazy { intent.getBooleanExtra(EXTRA_IS_LOCAL_FILE, true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_detail)
        val pdfView = findViewById<PDFView>(R.id.pdf_view)
        val tvPageCount = findViewById<TextView>(R.id.tv_page_count)

        pdfView.setWatermark(R.drawable.ic_default_watermark)

        if (mIsLocalFile)
            pdfView.showPdfFromPath(intent.getStringExtra(EXTRA_FILE_PATH) ?: return)
        else
            pdfView.showPdfFromUrl(intent.getStringExtra(EXTRA_FILE_URL) ?: return)

        pdfView.setOnPageChangedListener(object : PDFView.OnPageChangedListener {
            @SuppressLint("SetTextI18n")
            override fun onPageChanged(currentPageIndex: Int, totalPageCount: Int) {
                tvPageCount.text = "${currentPageIndex + 1}/$totalPageCount"
            }
        })
    }

}