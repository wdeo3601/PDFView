package com.example.pdfview

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val CHOOSE_PDF_REQUEST_CODE = 100
        private const val PERMISSION_REQUEST_CODE = 101
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.bt_sample).setOnClickListener {
            openSamplePdf()
        }

        findViewById<Button>(R.id.bt_choose_pdf).setOnClickListener {
            val checkSelfPermission = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            when {
                checkSelfPermission == PackageManager.PERMISSION_GRANTED -> choosePdfFromLocal()
                shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE) -> requestPermissions(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    PERMISSION_REQUEST_CODE
                )
                else -> Toast.makeText(this, "从设置里为该app打开权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openSamplePdf() {
        val filePath = filesDir.absolutePath + File.separator + "sample.pdf"
        copyAssetsToFiles(baseContext, "sample.pdf", filePath)
        PdfDetailActivity.startMeWithFilePath(this, filePath)
    }

    /**
     * 从assets目录中复制文件到app的files目录
     * @param  context  Context 使用CopyFiles类的Activity
     * @param  assetsFileName  将要复制的 assets 文件夹中文件的名字
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

    private fun choosePdfFromLocal() {
        val intent = Intent()
        intent.action = Intent.ACTION_OPEN_DOCUMENT
        intent.type = "*/*"
        val mimeTypes = arrayOf("application/pdf")
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        startActivityForResult(Intent.createChooser(intent, "choose file"), CHOOSE_PDF_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CHOOSE_PDF_REQUEST_CODE) return
        if (resultCode != Activity.RESULT_OK) return
        data ?: return
        val uri = data.data ?: return
        PdfDetailActivity.startMeWithFilePath(
            this,
            FileUtils.getFilePathByUri(this, uri) ?: return
        )

    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        )
            choosePdfFromLocal()
    }
}
