package com.wdeo3601.pdfview

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.*
import android.util.AttributeSet
import android.util.Log
import android.util.LruCache
import android.util.Range
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.jakewharton.disklrucache.DiskLruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Created on 2020/8/3.
 * @author wdeo3601
 * @description 原生pdf查看
 */
class PDFView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val TAG: String =
        PDFView::class.java.simpleName

    companion object {
        //线程池，异步处理pdf转换bitmap
        private val EXECUTOR_SERVICE = Executors.newFixedThreadPool(3)
    }

    /**
     * 触摸状态，单指触摸、多点触控、空闲状态
     */
    private enum class TouchState {
        SINGLE_POINTER,
        MULTI_POINTER,
        IDLE,
    }

    // pdf渲染器
    private var mPdfRenderer: PdfRenderer? = null

    //pdf文件路径
    private var mPdfName: String? = null

    // pdf占位用的矩形
    private val mPagePlaceHolders = mutableListOf<PageRect>()

    //正在加载的pdf页
    private val mLoadingPages = mutableListOf<DrawingPage>()

    //正在缩放显示的pdf页
    private var mScalingPages = mutableListOf<DrawingPage>()

    // pdf总宽度
    private var mPdfTotalWidth: Float = 0f

    // pdf总高度
    private var mPdfTotalHeight: Float = 0f

    //处理飞速滑动的手势识别器
    private val mGestureDetector by lazy {
        GestureDetector(
            context,
            OnPDFGestureListener(this)
        )
    }

    //处理pdf转换bitmap的handler
    private val mPDFHandler: PDFHandler

    //画笔
    private val mPDFPaint: Paint
    private val mDividerPaint: Paint

    //分隔线宽度（垂直分隔线和水平分隔线）
    private val mDividerHeight = dp2px(8).toFloat()

    //缩放相关
    private var mCanvasScale: Float = 1f //画布的缩放倍数
    private var mCanvasTranslate: PointF = PointF() //滑动+缩放产生的画布平移
    private var mMultiFingerCenterPointStart: PointF = PointF() //多点触控按下时，画布上的缩放中心点
    private var mScaleStart: Float = mCanvasScale //多点触控按下时，记录按下时的缩放倍数
    private var mMultiFingerDistanceStart: Float = 0f //多点触控按下时，记录按下时两个手指之间的间距
    private var mZoomTranslateStart: PointF = PointF() //多点触控按下时，记录此时的scrollXY

    //缩放配置参数
    private var mCanZoom = true //是否可以缩放
    private var mMaxScale = 10f //最大缩放倍数
    private var mMinScale = 1f //最小缩放倍数

    //滑动相关
    private var mFlingAnim: ValueAnimator? = null

    //当前的触摸状态
    private var mTouchState = TouchState.IDLE

    //当前正在显示的页的索引
    private var mCurrentPageIndex = 0

    //记录线程池中最新添加的任务
    private var mCreateLoadingPagesFuture: Future<*>? = null
    private var mCreateScalingPagesFuture: Future<*>? = null
    private var mInitPageFramesFuture: Future<*>? = null

    //page转换bitmap的缓存
    private var mOffscreenPageLimit = 2 //当前可见页的上下两边各缓存多少页，类似viewpager的属性
    private val mLoadingPagesBitmapMemoryCache: LruCache<String, Bitmap>//内存缓存
    private val mLoadingPagesBitmapDiskCache: DiskLruCache//磁盘缓存

    //滑动时的页面变动监听
    private var mOnPageChangedListener: OnPageChangedListener? = null

    init {
        //主线程初始化Handler
        mPDFHandler = PDFHandler(this)

        //初始化缓存
        mLoadingPagesBitmapMemoryCache = LruCache<String, Bitmap>(mOffscreenPageLimit * 2 + 1)
        mLoadingPagesBitmapDiskCache = DiskLruCache.open(context.cacheDir, 1, 1, 1024 * 1024 * 100)

        //初始化pdf画笔
        mPDFPaint = Paint()
        mPDFPaint.color = Color.WHITE
        mPDFPaint.isAntiAlias = true
        mPDFPaint.isFilterBitmap = true
        mPDFPaint.isDither = true

        //初始化分割线画笔
        mDividerPaint = Paint()
        mDividerPaint.color = Color.GRAY
        mDividerPaint.isAntiAlias = true
    }

    //region 对外暴露的方法
    /**
     * 打开网络pdf文件
     */
    fun showPdfFromUrl(fileUrl: String) {
        try {
            //从 fileUrl生成文件名，去files目录取
            val file = File("${context.filesDir}${File.separator}${md5(fileUrl)}.pdf")
            val filePath = file.path
            if (file.exists()) {
                //取到了，调用显示本地pdf的方法
                showPdfFromPath(filePath)
            } else {
                //取不到，开启任务下载pdf到本地
                EXECUTOR_SERVICE.submit(
                    PdfDownloadTask(
                        this,
                        fileUrl,
                        filePath
                    )
                )
            }
            //下载完成后获取到本地路径，再调用显示本地pdf的方法
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 打开本地pdf文件
     */
    fun showPdfFromPath(filePath: String) {
        try {
            val file = File(filePath)
            val parcelFileDescriptor =
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            closePdfRenderer()
            mPdfRenderer = PdfRenderer(parcelFileDescriptor)
            mPdfName = file.name
            mInitPageFramesFuture = null
            invalidate()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 设置当前页上下两边的缓存页个数
     * 默认上下各2个
     */
    fun setOffscreenPageLimit(limit: Int) {
        if (limit < 1) throw IllegalArgumentException("limit must >= 1")
        if (limit != mOffscreenPageLimit)
            mOffscreenPageLimit = limit
    }

    /**
     * 设置当前页索引改变回调
     */
    fun setOnPageChangedListener(listener: OnPageChangedListener) {
        this.mOnPageChangedListener = listener
    }

    /**
     * 是否支持缩放
     * 默认支持
     */
    fun isCanZoom(canZoom: Boolean) {
        this.mCanZoom = canZoom
    }

    /**
     * 设置最大缩放倍数
     */
    fun setMaxScale(maxScale: Float) {
        this.mMaxScale = min(maxScale, 20f)
    }

    //endregion

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSpecMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightSpecMode = MeasureSpec.getMode(heightMeasureSpec)
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val parentHeight = MeasureSpec.getSize(heightMeasureSpec)
        val resizeWidth = widthSpecMode != MeasureSpec.EXACTLY
        val resizeHeight = heightSpecMode != MeasureSpec.EXACTLY
        var width = parentWidth
        var height = parentHeight
        width = width.coerceAtLeast(suggestedMinimumWidth)
        height = height.coerceAtLeast(suggestedMinimumHeight)
        setMeasuredDimension(width, height)

        //初始化pdf页框架
        if (mInitPageFramesFuture == null)
            mInitPageFramesFuture = EXECUTOR_SERVICE.submit(
                InitPdfFramesTask(this)
            )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        //数据还没准备好，直接返回
        if (mPagePlaceHolders.isEmpty()) return

        canvas.save()

        //平移缩放
        canvas.translate(mCanvasTranslate.x, mCanvasTranslate.y)
        canvas.scale(mCanvasScale, mCanvasScale)

        //画占位图和分隔线
        drawPlaceHolderAndDivider(canvas)

        //画将要显示的完整page
        drawLoadingPages(canvas)

        //画边界分隔线
        drawEdgeDivider(canvas)

        //如果缩放了，画将要显示的缩放后的page部分区域
        drawScalingPages(canvas)
    }

    /**
     * 画page占位图和横向分隔线
     */
    private fun drawPlaceHolderAndDivider(canvas: Canvas) {
        mPagePlaceHolders.forEachIndexed { index, pageRect ->
            val fillWidthRect = pageRect.fillWidthRect
            //画占位页
            canvas.drawRect(fillWidthRect, mPDFPaint)
            //画页分隔
            if (index < mPagePlaceHolders.lastIndex)
                canvas.drawRect(
                    paddingLeft.toFloat(),
                    fillWidthRect.bottom,
                    measuredWidth - paddingRight.toFloat(),
                    fillWidthRect.bottom + mDividerHeight,
                    mDividerPaint
                )
        }
    }

    /**
     * 画完整显示的pdf页面
     */
    private fun drawLoadingPages(canvas: Canvas) {
        mLoadingPages.filter { page ->
            //即将缩放显示的页面，不绘制它的全页bitmap
            val isScaling = page.pageIndex in mScalingPages.map { it.pageIndex }
            page.pageRect?.fillWidthRect != null
                    && page.bitmap != null
                    && !isScaling
        }
            .forEach {
                val fillWidthScale = it.pageRect!!.fillWidthScale
                val fillWidthRect = it.pageRect.fillWidthRect
                canvas.save()
                canvas.translate(fillWidthRect.left, fillWidthRect.top)
                canvas.scale(fillWidthScale, fillWidthScale)
                canvas.drawBitmap(it.bitmap!!, 0f, 0f, mPDFPaint)
                canvas.restore()
            }
    }

    /**
     * 画缩放过得pdf页面部分区域
     */
    private fun drawScalingPages(canvas: Canvas) {
        canvas.restore()
        mScalingPages.filter {
            it.pageRect?.fillWidthRect != null && it.bitmap != null
        }
            .forEach {
                val fillWidthRect = it.pageRect!!.fillWidthRect
                canvas.drawBitmap(
                    it.bitmap!!,
                    fillWidthRect.left,
                    fillWidthRect.top,
                    mPDFPaint
                )
            }
    }

    /**
     * 画边界分隔线
     */
    private fun drawEdgeDivider(canvas: Canvas) {
        //画两边的分割线
        val firstPageRect = mPagePlaceHolders.first().fillWidthRect
        val lastPageRect = mPagePlaceHolders.last().fillWidthRect
        canvas.drawRect(
            paddingLeft.toFloat(),
            firstPageRect.top,
            paddingLeft.toFloat() + mDividerHeight,
            lastPageRect.bottom,
            mDividerPaint
        )
        canvas.drawRect(
            width - paddingLeft.toFloat() - mDividerHeight,
            firstPageRect.top,
            width - paddingLeft.toFloat(),
            lastPageRect.bottom,
            mDividerPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        clearScalingPages()
        var handled = false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                debug("onTouchEvent-ACTION_DOWN")
                mTouchState =
                    TouchState.SINGLE_POINTER
                //如果有正在执行的 fling 动画，就重置动画
                stopFlingAnimIfNeeded()
                mGestureDetector.onTouchEvent(event)
                handled = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                debug("onTouchEvent-ACTION_POINTER_DOWN")
                mTouchState =
                    TouchState.MULTI_POINTER
                //如果有正在执行的 fling 动画，就重置动画
                stopFlingAnimIfNeeded()
                handled = onZoomTouchEvent(event)
            }
            MotionEvent.ACTION_MOVE -> {
                debug("onTouchEvent-ACTION_MOVE")
                handled = when (mTouchState) {
                    TouchState.SINGLE_POINTER -> mGestureDetector.onTouchEvent(event)
                    TouchState.MULTI_POINTER -> onZoomTouchEvent(event)
                    else -> false
                }
            }
            MotionEvent.ACTION_UP -> {
                debug("onTouchEvent-ACTION_UP")
                handled = when (mTouchState) {
                    TouchState.SINGLE_POINTER -> {
                        val isFling = mGestureDetector.onTouchEvent(event)
                        if (!isFling) {
                            //单指滑动结束，处理滑动结束（无飞速滑动的情况）
                            submitCreateLoadingPagesTask()
                        }
                        true
                    }
                    TouchState.MULTI_POINTER -> onZoomTouchEvent(event)
                    else -> false
                }
                mTouchState = TouchState.IDLE
            }
        }
        return handled || super.onTouchEvent(event)
    }

    /**
     * 多指触摸，处理缩放
     */
    private fun onZoomTouchEvent(event: MotionEvent): Boolean {
        //如果没开启缩放，就不处理多点触控
        if (!mCanZoom) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                debug("onZoomTouchEvent-ACTION_POINTER_DOWN")
                //记录多点触控按下时的初始手指间距
                mMultiFingerDistanceStart =
                    distance(
                        event.getX(0),
                        event.getX(1),
                        event.getY(0),
                        event.getY(1)
                    )
                //记录按下时的缩放倍数
                mScaleStart = mCanvasScale
                //记录按下时的画布中心点
                mMultiFingerCenterPointStart.set(
                    (event.getX(0) + event.getX(1)) / 2,
                    (event.getY(0) + event.getY(1)) / 2
                )
                mZoomTranslateStart.set(mCanvasTranslate)

                return mCanZoom
            }
            MotionEvent.ACTION_MOVE -> {
                debug("onZoomTouchEvent-ACTION_MOVE")
                if (event.pointerCount < 2) return false
                val multiFingerDistanceEnd =
                    distance(
                        event.getX(0),
                        event.getX(1),
                        event.getY(0),
                        event.getY(1)
                    )
                val tempScale = (multiFingerDistanceEnd / mMultiFingerDistanceStart) * mScaleStart
                mCanvasScale = when (tempScale) {
                    in 0f..mMinScale -> mMinScale
                    in mMinScale..mMaxScale -> tempScale
                    else -> mMaxScale
                }

                val centerPointEndX = (event.getX(0) + event.getX(1)) / 2
                val centerPointEndY = (event.getY(0) + event.getY(1)) / 2

                val vLeftStart: Float = mMultiFingerCenterPointStart.x - mZoomTranslateStart.x
                val vTopStart: Float = mMultiFingerCenterPointStart.y - mZoomTranslateStart.y
                val vLeftNow: Float = vLeftStart * (mCanvasScale / mScaleStart)
                val vTopNow: Float = vTopStart * (mCanvasScale / mScaleStart)

                //判断滑动边界，重新设置滑动值
                val canTranslateXRange = getCanTranslateXRange()
                val canTranslateYRange = getCanTranslateYRange()
                val tempTranslateX = centerPointEndX - vLeftNow
                val tempTranslateY = centerPointEndY - vTopNow
                val nextTranslateX = when {
                    tempTranslateX in canTranslateXRange -> tempTranslateX
                    tempTranslateX > canTranslateXRange.upper -> canTranslateXRange.upper
                    else -> canTranslateXRange.lower
                }
                val nextTranslateY = when {
                    tempTranslateY in canTranslateYRange -> tempTranslateY
                    tempTranslateY > canTranslateYRange.upper -> canTranslateYRange.upper
                    else -> canTranslateYRange.lower
                }
                mCanvasTranslate.set(nextTranslateX, nextTranslateY)
                invalidate()
                //重新计算当前页索引
                calculateCurrentPageIndex()
                return true
            }
            MotionEvent.ACTION_UP -> {
                debug("onZoomTouchEvent-ACTION_UP")
                submitCreateLoadingPagesTask()
                return true
            }
        }
        return false
    }

    /**
     * 关闭pdf渲染器
     */
    private fun closePdfRenderer() {
        if (mPdfRenderer != null) {
            mPdfRenderer?.close()
            mPdfRenderer = null
        }
    }

    /**
     * 滑动或飞速滑动时，计算当前正在的pdf页索引
     */
    private fun calculateCurrentPageIndex() {
        val translateY = mCanvasTranslate.y
        for (index in mPagePlaceHolders.indices) {
            val pageRect = mPagePlaceHolders[index].fillWidthRect
            val offset = measuredHeight * 0.4 //pdf页占屏幕超过60%时，即为当前页
            if (abs(translateY) + offset > pageRect.top * mCanvasScale
                && abs(translateY) + offset <= (pageRect.bottom + mDividerHeight) * mCanvasScale
            ) {
                if (index != mCurrentPageIndex) {
                    mOnPageChangedListener?.onPageChanged(index, mPagePlaceHolders.size)
                }
                mCurrentPageIndex = index
                debug("calculateCurrentPageIndex-mCurrentPageIndex:$mCurrentPageIndex")
                return
            }
        }
        debug("calculateCurrentPageIndex-LoopFinish:$mCurrentPageIndex")
    }

    /**
     * 开始飞速滑动的动画
     */
    private fun startFlingAnim(distanceX: Float, distanceY: Float) {
        //根据每毫秒20像素来计算动画需要的时间
        var animDuration = (max(abs(distanceX), abs(distanceY)) / 20).toLong()
        //时间最短不能小于100毫秒
        when (animDuration) {
            in 0 until 100 -> animDuration = 400
            in 100 until 600 -> animDuration = 600
        }

        debug("startFlingAnim--distanceX-$distanceX--distanceY-$distanceY--animDuration-$animDuration")
        mFlingAnim = ValueAnimator().apply {
            setFloatValues(0f, 1f)
            duration = animDuration
            interpolator = DecelerateInterpolator()
            addUpdateListener(
                PDFFlingAnimUpdateListener(
                    this@PDFView,
                    distanceX,
                    distanceY
                )
            )
            addListener(
                PdfFlingAnimListenerAdapter(
                    this@PDFView
                )
            )
            start()
        }
    }

    /**
     * 提交创建pdf页的任务到线程池
     * 如果线程池里有未执行或正在执行的任务，取消那个任务
     */
    private fun submitCreateLoadingPagesTask() {
        if (mCreateLoadingPagesFuture?.isDone != true)
            mCreateLoadingPagesFuture?.cancel(true)
        mCreateLoadingPagesFuture =
            EXECUTOR_SERVICE.submit(
                PdfPageToBitmapTask(this)
            )
    }

    /**
     * 提交创建pdf页的任务到线程池
     * 如果线程池里有未执行或正在执行的任务，取消那个任务
     */
    private fun submitCreateScalingPagesTask() {
        //只有在滑动状态为空闲的时候，才去创建缩放的 pdf 页的 bitmap
        if (mTouchState != TouchState.IDLE) return

        if (mCreateScalingPagesFuture?.isDone != true)
            mCreateScalingPagesFuture?.cancel(true)
        mCreateScalingPagesFuture =
            EXECUTOR_SERVICE.submit(
                CreateScalingPageBitmapTask(this)
            )
    }

    /**
     * 提交创建pdf页的任务到线程池
     * 如果线程池里有未执行或正在执行的任务，取消那个任务
     */
    private fun clearScalingPages() {
        if (mCreateScalingPagesFuture?.isDone != true)
            mCreateScalingPagesFuture?.cancel(true)
        mScalingPages.clear()
        invalidate()
    }

    /**
     * 停止飞速滑动的动画
     */
    private fun stopFlingAnimIfNeeded() {
        mFlingAnim?.cancel()
    }

    /**
     * 获取x轴可平移的间距
     */
    private fun getCanTranslateXRange(): Range<Float> {
        return Range(min(-(mCanvasScale * mPdfTotalWidth - width), 0f), 0f)
    }

    /**
     * 获取y轴可平移的间距
     */
    private fun getCanTranslateYRange(): Range<Float> {
        return Range(min(-(mCanvasScale * mPdfTotalHeight - height), 0f), 0f)
    }

    /**
     * 打印日志
     */
    private fun debug(message: String, vararg args: Any) {
        Log.d(TAG, String.format(message, *args))
    }

    /**
     * Pythagoras distance between two points.
     */
    private fun distance(
        x0: Float,
        x1: Float,
        y0: Float,
        y1: Float
    ): Float {
        val x = x0 - x1
        val y = y0 - y1
        return sqrt(x * x + y * y.toDouble()).toFloat()
    }

    /**
     * For debug overlays. Scale pixel value according to screen density.
     */
    private fun dp2px(dp: Int): Int {
        return (resources.displayMetrics.density * dp + 0.5).toInt()
    }

    /**
     * 异步处理 pdf_page to bitmap
     */
    private class PDFHandler(pdfView: PDFView) : Handler() {

        companion object {
            const val MESSAGE_INIT_PDF_PLACE_HOLDER = 1
            const val MESSAGE_CREATE_LOADING_PDF_BITMAP = 2
            const val MESSAGE_CREATE_SCALED_BITMAP = 3
            const val MESSAGE_PDF_DOWNLOAD_SUCCESS = 4
        }

        private val mWeakReference = WeakReference(pdfView)
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            val pdfView = mWeakReference.get() ?: return
            when (msg.what) {
                MESSAGE_PDF_DOWNLOAD_SUCCESS -> {
                    //下载完成后，触发重新测量，创建page框架数据
                    pdfView.requestLayout()
                    pdfView.showPdfFromPath(msg.obj as String)
                }
                MESSAGE_INIT_PDF_PLACE_HOLDER -> {
                    pdfView.debug("handleMessage-MESSAGE_INIT_PDF_PLACE_HOLDER")
                    val tempPagePlaceHolders = msg.data.getParcelableArrayList<PageRect>("list")
                    if (!tempPagePlaceHolders.isNullOrEmpty()) {
                        pdfView.mPdfTotalHeight = tempPagePlaceHolders.last().fillWidthRect.bottom
                        pdfView.mPdfTotalWidth = pdfView.width.toFloat()
                        pdfView.mPagePlaceHolders.addAll(tempPagePlaceHolders)
                        pdfView.invalidate()
                        pdfView.submitCreateLoadingPagesTask()
                        //初始化时触发页面变动回调
                        pdfView.mOnPageChangedListener?.onPageChanged(
                            pdfView.mCurrentPageIndex,
                            tempPagePlaceHolders.size
                        )
                    }
                }
                MESSAGE_CREATE_LOADING_PDF_BITMAP -> {
                    pdfView.debug("handleMessage-MESSAGE_CREATE_LOADING_PDF_BITMAP-currentPageIndex:${pdfView.mCurrentPageIndex}")
                    val calculatedPageIndex = msg.data.getInt("index")
                    val tempLoadingPages = msg.data.getParcelableArrayList<DrawingPage>("list")
                    if (pdfView.mCurrentPageIndex != calculatedPageIndex) {
                        return
                    }
                    if (!tempLoadingPages.isNullOrEmpty()) {
                        pdfView.mLoadingPages.clear()
                        pdfView.mLoadingPages.addAll(tempLoadingPages)
                        pdfView.invalidate()

                        //渲染模糊页面成功后，再开始渲染屏幕上显示的pdf块的高清 bitmap
                        pdfView.submitCreateScalingPagesTask()
                    }
                }
                MESSAGE_CREATE_SCALED_BITMAP -> {
                    val calculatedPageIndex = msg.data.getInt("index")
                    val tempScalingPages = msg.data.getParcelableArrayList<DrawingPage>("list")
                    if (pdfView.mCurrentPageIndex != calculatedPageIndex) {
                        return
                    }
                    if (!tempScalingPages.isNullOrEmpty()) {
                        pdfView.mScalingPages.clear()
                        pdfView.mScalingPages.addAll(tempScalingPages)
                        pdfView.invalidate()
                    }
                }
            }
        }
    }

    /**
     * 处理触摸滑动和飞速滑动
     * P.S. 只处理单点触摸的操作
     */
    private class OnPDFGestureListener(pdfView: PDFView) :
        GestureDetector.SimpleOnGestureListener() {
        private val mWeakReference = WeakReference(pdfView)

        override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
            mWeakReference.get()?.performClick()
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent?,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            val pdfView = mWeakReference.get() ?: return false

            //判断滑动边界，重新设置滑动值
            val canTranslateXRange = pdfView.getCanTranslateXRange()
            val canTranslateYRange = pdfView.getCanTranslateYRange()
            val tempTranslateX = pdfView.mCanvasTranslate.x - distanceX
            val tempTranslateY = pdfView.mCanvasTranslate.y - distanceY
            val nextTranslateX = when {
                tempTranslateX in canTranslateXRange -> tempTranslateX
                tempTranslateX > canTranslateXRange.upper -> canTranslateXRange.upper
                else -> canTranslateXRange.lower
            }
            val nextTranslateY = when {
                tempTranslateY in canTranslateYRange -> tempTranslateY
                tempTranslateY > canTranslateYRange.upper -> canTranslateYRange.upper
                else -> canTranslateYRange.lower
            }

            //3.开始滑动，重绘
            pdfView.mCanvasTranslate.set(nextTranslateX, nextTranslateY)
            pdfView.invalidate()
            //4.重新计算当前页索引
            pdfView.calculateCurrentPageIndex()
            pdfView.debug("onScroll-distanceX:${distanceX}-distanceY:${distanceY}")
            //5. 滑动结束监听回调，创建page位图数据（需要再 onTouchEvent 中判断滑动结束,所以这里返回 false）
            return false
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent?,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val pdfView = mWeakReference.get() ?: return false
            mWeakReference.get()?.debug("onFling-velocityX:${velocityX}-velocityY:${velocityY}")
            if (
                e1 != null && e2 != null
                && (abs(e1.x - e2.x) > 100 || abs(e1.y - e2.y) > 100)
                && (abs(velocityX) > 500 || abs(velocityY) > 500)
            ) {
                val canTranslateXRange = pdfView.getCanTranslateXRange()
                val canTranslateYRange = pdfView.getCanTranslateYRange()
                val tempTranslateX = pdfView.mCanvasTranslate.x + velocityX * 0.75f
                val tempTranslateY = pdfView.mCanvasTranslate.y + velocityY * 0.75f
                val endTranslateX = when {
                    tempTranslateX in canTranslateXRange -> tempTranslateX
                    tempTranslateX > canTranslateXRange.upper -> canTranslateXRange.upper
                    else -> canTranslateXRange.lower
                }
                val endTranslateY = when {
                    tempTranslateY in canTranslateYRange -> tempTranslateY
                    tempTranslateY > canTranslateYRange.upper -> canTranslateYRange.upper
                    else -> canTranslateYRange.lower
                }

                val distanceX = endTranslateX - pdfView.mCanvasTranslate.x
                val distanceY = endTranslateY - pdfView.mCanvasTranslate.y

                pdfView.startFlingAnim(distanceX, distanceY)
                return true
            }

            return super.onFling(e1, e2, velocityX, velocityY)
        }
    }

    /**
     * 飞速滑动动画更新回调
     */
    private class PDFFlingAnimUpdateListener(
        pdfView: PDFView,
        private val distanceX: Float,
        private val distanceY: Float
    ) : ValueAnimator.AnimatorUpdateListener {
        private val mWeakReference = WeakReference(pdfView)
        private val lastCanvasTranslate = PointF(
            pdfView.mCanvasTranslate.x,
            pdfView.mCanvasTranslate.y
        )

        override fun onAnimationUpdate(animation: ValueAnimator) {
            val pdfView = mWeakReference.get() ?: return

            //飞速滑动时，不渲染缩放的 bitmap
            pdfView.clearScalingPages()

            val percent = animation.animatedValue as Float
            pdfView.mCanvasTranslate.x = lastCanvasTranslate.x + distanceX * percent
            pdfView.mCanvasTranslate.y = lastCanvasTranslate.y + distanceY * percent
            pdfView.invalidate()
            //重新计算当前页索引
            pdfView.calculateCurrentPageIndex()
        }
    }

    private class PdfFlingAnimListenerAdapter(pdfView: PDFView) : AnimatorListenerAdapter() {
        private val mWeakReference = WeakReference(pdfView)

        override fun onAnimationCancel(animation: Animator?) {
            preLoadPdf()
        }

        override fun onAnimationEnd(animation: Animator?) {
            preLoadPdf()
        }

        private fun preLoadPdf() {
            val pdfView = mWeakReference.get() ?: return
            pdfView.submitCreateLoadingPagesTask()
        }
    }

    /**
     * 线程任务
     * 创建要显示的pdf页的bitmap集合
     * 有本地缓存的话用本地缓存，没有缓存的话从pdf生成bitmap后缓存
     */
    private class PdfPageToBitmapTask(pdfView: PDFView) : Runnable {
        private val mWeakReference = WeakReference(pdfView)
        override fun run() {
            val pdfView = mWeakReference.get() ?: return
            val tempLoadingPages = arrayListOf<DrawingPage>()
            val pdfRenderer = pdfView.mPdfRenderer ?: return
            val pagePlaceHolders = pdfView.mPagePlaceHolders
            val currentPageIndex = pdfView.mCurrentPageIndex
            val startLoadingIndex = max(0, currentPageIndex - pdfView.mOffscreenPageLimit)
            val endLoadingIndex =
                min(currentPageIndex + pdfView.mOffscreenPageLimit, pagePlaceHolders.lastIndex)
            for (index in startLoadingIndex..endLoadingIndex) {
                val pageRect = pagePlaceHolders[index]
                val fillWidthRect = pageRect.fillWidthRect
                var bitmap = pdfView.getLoadingPagesBitmapFromCache(index)
                if (bitmap == null) {
                    //3.本地缓存没拿到，从pdf渲染器创建bitmap
                    val page = pdfRenderer.openPage(index)
                    bitmap = Bitmap.createBitmap(
                        (fillWidthRect.width() / pageRect.fillWidthScale).toInt(),
                        (fillWidthRect.height() / pageRect.fillWidthScale).toInt(),
                        Bitmap.Config.ARGB_8888
                    )
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    //新创建的bitmap，存到内存缓存和本地缓存
                    pdfView.putLoadingPagesBitmapToCache(index, bitmap)
                }
                tempLoadingPages.add(
                    DrawingPage(
                        pageRect,
                        bitmap,
                        index
                    )
                )
            }

            val message = Message()
            message.what =
                PDFHandler.MESSAGE_CREATE_LOADING_PDF_BITMAP
            message.data.putInt("index", currentPageIndex)
            message.data.putParcelableArrayList("list", tempLoadingPages)
            pdfView.mPDFHandler.sendMessage(message)
        }
    }

    /**
     * 线程任务
     * 创建正在缩放的显示的pdf页的bitmap
     */
    private class CreateScalingPageBitmapTask(pdfView: PDFView) : Runnable {
        private val mWeakReference = WeakReference(pdfView)
        override fun run() {
            val pdfView = mWeakReference.get() ?: return
            val pdfRenderer = pdfView.mPdfRenderer ?: return
            val pagePlaceHolders = pdfView.mPagePlaceHolders

            val currentPageIndex = pdfView.mCurrentPageIndex
            val currentTranslateY = pdfView.mCanvasTranslate.y
            val currentPageTop = pagePlaceHolders[currentPageIndex].fillWidthRect.top
            pdfView.debug("CreateScalingPageBitmapTask--currentTranslateY:$currentTranslateY-currentPageTop:$currentPageTop")

            val tempScalingPages = arrayListOf<DrawingPage>()

            var startIndex = currentPageIndex
            run {
                for (index in currentPageIndex downTo 0) {
                    if (pagePlaceHolders[index].fillWidthRect.bottom * pdfView.mCanvasScale < abs(
                            currentTranslateY
                        )
                    ) {
                        return@run
                    }
                    startIndex = index
                }
            }
            var endIndex = startIndex
            run {
                for (index in startIndex..pagePlaceHolders.lastIndex) {
                    if (pagePlaceHolders[index].fillWidthRect.top * pdfView.mCanvasScale > abs(
                            currentTranslateY
                        ) + pdfView.measuredHeight - pdfView.paddingTop - pdfView.paddingBottom
                    ) {
                        return@run
                    }
                    endIndex = index
                }
            }

            for (index in startIndex..endIndex) {
                val placeHolderPageRect = pagePlaceHolders[index]
                val fillWidthRect = placeHolderPageRect.fillWidthRect

                //创建缩放bitmap的位置信息
                val scalingRectTop =
                    max(fillWidthRect.top * pdfView.mCanvasScale - abs(currentTranslateY), 0f)
                val scalingRectBottom =
                    min(
                        fillWidthRect.bottom * pdfView.mCanvasScale - abs(currentTranslateY),
                        pdfView.measuredHeight - pdfView.paddingTop - pdfView.paddingBottom.toFloat()
                    )

                //处理滑动到分隔线停止的情况
                if (scalingRectBottom <= scalingRectTop) continue

                val scalingRect = RectF(
                    0f,
                    scalingRectTop,
                    pdfView.measuredWidth - pdfView.paddingLeft - pdfView.paddingRight.toFloat(),
                    scalingRectBottom
                )

                val bitmap = Bitmap.createBitmap(
                    scalingRect.width().toInt(),
                    scalingRect.height().toInt(),
                    Bitmap.Config.ARGB_8888
                )
                val matrix = Matrix()
                //page页真实的缩放倍数=原始页缩放到屏幕宽度的缩放倍数*画布的缩放倍数
                val scale = placeHolderPageRect.fillWidthScale * pdfView.mCanvasScale
                matrix.postScale(scale, scale)
                //平移，因为取的是已经缩放过的page页，所以平移量跟缩放后的画布平移量保持一致
                matrix.postTranslate(
                    pdfView.mCanvasTranslate.x + (pdfView.paddingLeft + pdfView.mDividerHeight) * pdfView.mCanvasScale,
                    min(fillWidthRect.top * pdfView.mCanvasScale + currentTranslateY, 0f)
                )
                val page = pdfRenderer.openPage(index)
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                tempScalingPages.add(
                    DrawingPage(
                        PageRect(fillWidthRect = scalingRect),
                        bitmap,
                        index
                    )
                )
            }

            val message = Message()
            message.what =
                PDFHandler.MESSAGE_CREATE_SCALED_BITMAP
            message.data.putInt("index", currentPageIndex)
            message.data.putParcelableArrayList("list", tempScalingPages)
            pdfView.mPDFHandler.sendMessage(message)
        }
    }

    /**
     * 线程任务
     * 初始化pdf页框架数据
     */
    private class InitPdfFramesTask(pdfView: PDFView) : Runnable {
        private val mWeakReference = WeakReference(pdfView)
        override fun run() {
            val pdfView = mWeakReference.get() ?: return
            val pdfRenderer =
                pdfView.mPdfRenderer ?: throw NullPointerException("pdfRenderer is null!")
            val tempPagePlaceHolders = arrayListOf<PageRect>()

            var pdfTotalHeight = 0f
            val left = pdfView.paddingLeft.toFloat() + pdfView.mDividerHeight
            val right =
                pdfView.measuredWidth.toFloat() - pdfView.paddingRight.toFloat() - pdfView.mDividerHeight
            var fillWidthScale: Float
            var scaledHeight: Float
            for (index in 0 until pdfRenderer.pageCount) {
                val page = pdfRenderer.openPage(index)
                fillWidthScale = (right - left) / page.width.toFloat()
                scaledHeight = page.height * fillWidthScale
                //预留分割线的高度
                if (index != 0)
                    pdfTotalHeight += pdfView.mDividerHeight
                val rect = RectF(left, pdfTotalHeight, right, pdfTotalHeight + scaledHeight)
                pdfTotalHeight = rect.bottom
                tempPagePlaceHolders.add(
                    PageRect(
                        fillWidthScale,
                        rect
                    )
                )
                page.close()
            }

            val message = Message()
            message.what =
                PDFHandler.MESSAGE_INIT_PDF_PLACE_HOLDER
            message.data.putParcelableArrayList("list", tempPagePlaceHolders)
            pdfView.mPDFHandler.sendMessage(message)
        }
    }

    /**
     * 线程任务
     * 下载网络pdf
     */
    private class PdfDownloadTask(
        pdfView: PDFView,
        private val fileUrl: String,
        private val filePath: String
    ) : Runnable {
        private val mWeakReference = WeakReference(pdfView)
        override fun run() {
            val pdfView = mWeakReference.get() ?: return
            try {
                val httpURLConnection = URL(fileUrl).openConnection() as HttpURLConnection
                //读取网络内容到字节数组
                val inputStream = httpURLConnection.inputStream
                val buffer = ByteArray(1024)
                var len: Int
                val bos = ByteArrayOutputStream()
                len = inputStream.read(buffer)
                while (len != -1) {
                    bos.write(buffer, 0, len)
                    len = inputStream.read(buffer)
                }
                bos.close()
                inputStream.close()

                //字节数组写入本地文件
                val file = File(filePath)
                val fos = FileOutputStream(file)
                fos.write(bos.toByteArray())
                fos.close()

                val message = Message()
                message.what =
                    PDFHandler.MESSAGE_PDF_DOWNLOAD_SUCCESS
                message.obj = filePath
                pdfView.mPDFHandler.sendMessage(message)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 将要绘制的pdf页数据
     * 位置信息、bitmap
     */
    private data class DrawingPage(
        val pageRect: PageRect?,
        val bitmap: Bitmap?,
        val pageIndex: Int
    ) : Parcelable {
        constructor(parcel: Parcel) : this(
            parcel.readParcelable(PageRect::class.java.classLoader),
            parcel.readParcelable(Bitmap::class.java.classLoader),
            parcel.readInt()
        )

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeParcelable(pageRect, flags)
            parcel.writeParcelable(bitmap, flags)
            parcel.writeInt(pageIndex)
        }

        override fun describeContents(): Int {
            return 0
        }

        companion object CREATOR : Parcelable.Creator<DrawingPage> {
            override fun createFromParcel(parcel: Parcel): DrawingPage {
                return DrawingPage(parcel)
            }

            override fun newArray(size: Int): Array<DrawingPage?> {
                return arrayOfNulls(size)
            }
        }
    }

    /**
     * page的尺寸信息
     */
    private class PageRect(
        val fillWidthScale: Float = 1f, //缩放到屏幕宽度的缩放倍数
        val fillWidthRect: RectF //缩放到屏幕宽度的page的尺寸数据
    ) : Parcelable {
        constructor(parcel: Parcel) : this(
            parcel.readFloat(),
            parcel.readParcelable(RectF::class.java.classLoader) ?: RectF()
        )

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeFloat(fillWidthScale)
            parcel.writeParcelable(fillWidthRect, flags)
        }

        override fun describeContents(): Int {
            return 0
        }

        companion object CREATOR : Parcelable.Creator<PageRect> {
            override fun createFromParcel(parcel: Parcel): PageRect {
                return PageRect(parcel)
            }

            override fun newArray(size: Int): Array<PageRect?> {
                return arrayOfNulls(size)
            }
        }

    }

    interface OnPageChangedListener {
        fun onPageChanged(currentPageIndex: Int, totalPageCount: Int)
    }

    //region 缓存存取相关
    /**
     * 从缓存获取将要加载的page的bitmap
     */
    private fun getLoadingPagesBitmapFromCache(pageIndex: Int): Bitmap? {
        val key = getCachedKey(pageIndex)
        var bitmap = getLoadingPagesBitmapFromMemory(key)
        if (bitmap == null) {
            //2.内存缓存没拿到，从本地缓存拿
            bitmap = getLoadingPagesBitmapToDisk(key)
        }
        return bitmap
    }

    /**
     * 从内存缓存获取loadingPage 的 bitmap
     */
    private fun getLoadingPagesBitmapFromMemory(key: String): Bitmap? {
        return mLoadingPagesBitmapMemoryCache.get(key)
    }

    private fun getLoadingPagesBitmapToDisk(key: String): Bitmap? {
        try {
            val snapShot = mLoadingPagesBitmapDiskCache.get(key)
            snapShot ?: return null
            val fileInputStream = snapShot.getInputStream(0) as FileInputStream
            val fileDescriptor = fileInputStream.fd

            //create bitmap from disk
            val options = BitmapFactory.Options()
            val bitmap =
                BitmapFactory.decodeFileDescriptor(fileDescriptor, null, options)

            if (bitmap != null)
                putLoadingPagesBitmapToMemory(key, bitmap)

            return bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * 把 page 的 bitmap 缓存起来
     */
    private fun putLoadingPagesBitmapToCache(pageIndex: Int, bitmap: Bitmap) {
        val key = getCachedKey(pageIndex)
        //缓存到内存
        putLoadingPagesBitmapToMemory(key, bitmap)
        //缓存到本地
        putLoadingPagesBitmapToDisk(key, bitmap)
    }

    private fun putLoadingPagesBitmapToMemory(key: String, bitmap: Bitmap) {
        mLoadingPagesBitmapMemoryCache.put(key, bitmap)
    }

    private fun putLoadingPagesBitmapToDisk(key: String, bitmap: Bitmap) {
        try {
            val edit = mLoadingPagesBitmapDiskCache.edit(key)
            val outputStream = edit.newOutputStream(0)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            edit.commit()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 获取缓存key
     * md5(文件名_页面索引)
     */
    private fun getCachedKey(pageIndex: Int): String {
        val cacheKey = try {
            md5("${mPdfName}_$pageIndex")
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
            "${mPdfName.hashCode()}_$pageIndex"
        }
        debug("getCachedKey--pageIndex:$pageIndex--cacheKey:$cacheKey")
        return cacheKey
    }

    /**
     * 生成md5
     */
    private fun md5(value: String): String {
        val messageDigest =
            MessageDigest.getInstance("MD5")
        messageDigest.update(value.toByteArray())
        return BigInteger(1, messageDigest.digest()).toString(16)
    }
    //endregion
}