package com.example.pdfview

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.wdeo3601.pdfview.PDFView
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val pdfView = findViewById<PDFView>(R.id.pdf_view)
        val tvPageCount = findViewById<TextView>(R.id.tv_page_count)

        val filePath = filesDir.absolutePath + File.separator + "sample.pdf"
        copyAssetsToFiles(baseContext, "sample.pdf", filePath)
        pdfView.showPdfFromPath(filePath)

//        pdfView.showPdfFromUrl("https://github.com/wdeo3601/PDFView/raw/master/sample.pdf")

        pdfView.setOnPageChangedListener(object : PDFView.OnPageChangedListener {
            @SuppressLint("SetTextI18n")
            override fun onPageChanged(currentPageIndex: Int, totalPageCount: Int) {
                tvPageCount.text = "${currentPageIndex + 1}/$totalPageCount"
            }
        })
    }

    /**
     * 从assets目录中复制文件到app的files目录
     * @param  context  Context 使用CopyFiles类的Activity
     * @param  oldPath  String  原文件路径  如：/aa
     * @param  newPath  String  复制后路径  如：xx:/bb/cc
     */
    private fun copyAssetsToFiles(
        context: Context,
        assetsFileName: String,
        newPath: String
    ) {
        try {
            val `is`: InputStream = context.assets.open(assetsFileName)
            val fos = FileOutputStream(File(newPath))
            val buffer = ByteArray(1024)
            var byteCount = 0
            while (`is`.read(buffer)
                    .also { byteCount = it } != -1
            ) { //循环从输入流读取 buffer字节
                fos.write(buffer, 0, byteCount) //将读取的输入流写入到输出流
            }
            fos.flush() //刷新缓冲区
            `is`.close()
            fos.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
