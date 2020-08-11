package com.example.pdfview

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.wdeo3601.pdfview.PDFView
import java.io.File

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val pdfView = findViewById<PDFView>(R.id.pdf_view)
        val tvPageCount = findViewById<TextView>(R.id.tv_page_count)
//        val filePath = filesDir.absolutePath + File.separator + "天星教育-电子版权.pdf"
        val filePath = filesDir.absolutePath + File.separator + "PHP 7从入门到精通.pdf"
//        val filePath = filesDir.absolutePath + File.separator + "simple.pdf"
//        pdfView.showPdfFromPath(filePath)
        pdfView.showPdfFromUrl("https://github.com/wdeo3601/PDFView/raw/master/sample.pdf")
        pdfView.setOnPageChangedListener(object : PDFView.OnPageChangedListener{
            @SuppressLint("SetTextI18n")
            override fun onPageChanged(currentPageIndex: Int, totalPageCount: Int) {
                tvPageCount.text = "${currentPageIndex+1}/$totalPageCount"
            }
        })
    }
}
