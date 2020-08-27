# PDFView
一个安卓平台的 PDF 查看器，支持滑动、缩放、三级缓存、放大展示高清图、支持一些功能配置。
* 支持滑动、缩放、缩放后的滑动、缩放后的平移
* 支持放大后查看高清 PDF 页
* 支持设置预加载 PDF 页的个数，有效避免 OOM
* 使用线程池 + Handler ，异步处理 PDF 转换 Bitmap 的相关操作
* 使用 LruCache、DiskLruCache 缓存转换过的 PDF 页的 Bitmap
* 支持从本地打开 PDF 文件、打开网络 PDF 文件
* 支持为文档添加水印

**使用系统自带的 `PdfRenderer` 来处理的 pdf 原始文件渲染，`最低支持安卓5.0`**

## 效果图  
![正常滑动](https://img-blog.csdnimg.cn/20200822153707459.gif)
![放大后滑动](https://img-blog.csdnimg.cn/20200822153648757.gif)
![放大后平移](https://img-blog.csdnimg.cn/20200822153623644.gif)

## 使用

### 依赖引入
```
implementation 'com.wdeo3601:pdf-view:1.0.4'
```

### 写 xml

```
    <com.wdeo3601.pdfview.PDFView
        android:id="@+id/pdf_view"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />
```

### 代码中使用

```kotlin

// 设置当前显示页的前后缓存个数，效果类似 ViewPager 的这个属性
pdfView.setOffscreenPageLimit(2)

// 是否支持缩放
pdfView.isCanZoom(true)

// 设置最大缩放倍数,最大支持20倍
pdfView.setMaxScale(10f)

// 添加水印
pdfView.setWatermark(R.drawable.ic_default_watermark)

// 设置当前页变化的回调监听
pdfView.setOnPageChangedListener(object : PDFView.OnPageChangedListener {
    @SuppressLint("SetTextI18n")
    override fun onPageChanged(currentPageIndex: Int, totalPageCount: Int) {
        // show current page number
    }
})

// 从本地文件打开 pdf
pdfView.showPdfFromPath(filePath)
// 从网络打开 pdf
//pdfView.showPdfFromUrl("https://github.com/wdeo3601/PDFView/raw/master/sample.pdf")

```

### 查看更多

[7天1000行代码从零开发一个 Android PDF 查看器](https://blog.csdn.net/Captive_Rainbow_/article/details/108169413)

### License

    Copyright 2020 wdeo3601

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
