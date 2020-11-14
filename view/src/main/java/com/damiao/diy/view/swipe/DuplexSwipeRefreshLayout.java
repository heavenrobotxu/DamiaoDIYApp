package com.damiao.diy.view.swipe;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Transformation;
import android.widget.AbsListView;
import android.widget.ListView;

import androidx.annotation.ColorInt;
import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Px;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;
import androidx.core.view.NestedScrollingChild;
import androidx.core.view.NestedScrollingChildHelper;
import androidx.core.view.NestedScrollingParent;
import androidx.core.view.NestedScrollingParentHelper;
import androidx.core.view.ViewCompat;
import androidx.core.widget.ListViewCompat;
import androidx.swiperefreshlayout.widget.CircularProgressDrawable;

/**
 * 改造原先V4包中的SwipeRefreshLayout，让它不仅能支持下拉刷新，还能进一步支持上拉加载功能，上拉时可弹出同样的指示
 * 球并不停旋转，提供和原先下拉刷新一致的上拉加载回调、手动设置加载完成等功能。并兼容了嵌套滑动，保持了和原先下拉刷新
 * 一致的嵌套滑动处理逻辑
 */
public class DuplexSwipeRefreshLayout extends ViewGroup implements NestedScrollingParent,
        NestedScrollingChild {
    //指示球样式：LARGE
    public static final int LARGE = CircularProgressDrawable.LARGE;
    //指示球样式：默认
    public static final int DEFAULT = CircularProgressDrawable.DEFAULT;

    @VisibleForTesting
    static final int CIRCLE_DIAMETER = 40;
    @VisibleForTesting
    static final int CIRCLE_DIAMETER_LARGE = 56;

    private static final String LOG_TAG = androidx.swiperefreshlayout.widget.SwipeRefreshLayout.class.getSimpleName();

    //指示球完全显示的透明度
    private static final int MAX_ALPHA = 255;
    //指示球的低透明度值：MAX / 3
    private static final int STARTING_PROGRESS_ALPHA = (int) (.3f * MAX_ALPHA);

    private static final float DECELERATE_INTERPOLATION_FACTOR = 2f;

    private static final int INVALID_POINTER = -1;
    //拖动速率，即手指拖动距离和指示球跟随响应移动距离的比例关系
    private static final float DRAG_RATE = .5f;

    //指示球内部的进度角度，默认值为80%，若设置为1，则指示球可以随着滑动合成一个完整的圆
    private static final float MAX_PROGRESS_ANGLE = .8f;
    //指示球缩小到消失动画执行时间
    private static final int SCALE_DOWN_DURATION = 150;
    //指示球透明度动画执行时间
    private static final int ALPHA_ANIMATION_DURATION = 300;

    private static final int ANIMATE_TO_TRIGGER_DURATION = 200;
    //指示球弹回初始位置执行时间
    private static final int ANIMATE_TO_START_DURATION = 200;

    //默认的指示球背景，乳白色
    private static final int CIRCLE_BG_LIGHT = 0xFFFAFAFA;
    //默认的指示球刷新距离，单位是dp
    private static final int DEFAULT_CIRCLE_TARGET = 64;
    //唯一子View，即响应刷新事件的内部容器，一般是ListView、RecycleView等
    private View mTarget; // the target of the gesture

    //顶部指示球刷新回调监听
    private OnRefreshListener mTopListener;
    //底部指示球刷新回调监听
    private OnRefreshListener mBottomListener;

    //顶部指示球是否正在刷新中
    boolean mTopRefreshing = false;
    //底部指示球是否正在刷新中
    boolean mBottomRefreshing = false;
    //最小滑动阈值
    private int mTouchSlop;
    //指示球的最大滑动距离（超过这个距离后，指示球将不再继续滑动）
    private float mTotalDragDistance = -1;

    //若嵌套滑动开启，用来表示ScrollingChild总的未消费的纵向滑动偏移量，这个total值会决定指示球需要移动到的目标位置
    private float mTotalUnconsumed;
    //嵌套滑动父容器辅助类，目的是为了兼容Android 5.0以下
    private final NestedScrollingParentHelper mNestedScrollingParentHelper;
    //嵌套滑动子View辅助类，目的是为了兼容Android 5.0以下
    private final NestedScrollingChildHelper mNestedScrollingChildHelper;
    //Swipe的父容器消耗的滑动值数组
    private final int[] mParentScrollConsumed = new int[2];

    private final int[] mParentOffsetInWindow = new int[2];
    //是否处在嵌套滑动中标志位
    private boolean mNestedScrollInProgress;
    //顶部指示球正在响应嵌套滑动事件
    private boolean mTopViewNestedScrollDragging;
    //底部指示球正在响应嵌套滑动事件
    private boolean mBottomViewNestedScrollDragging;
    //指示球放大动画执行时间
    private int mMediumAnimationDuration;
    //当前顶部指示球的y轴偏移量(相对于y轴原点)
    int mCurrentTargetOffsetTop;
    //当前底部指示球的y轴偏移量(相对于y轴原点)
    int mCurrentTargetOffsetBottom;

    //ACTION_DOWN触发时的初始y轴坐标(DOWN触发位置加touchSlot的和)
    private float mInitialMotionY;
    //ACTION_DOWN触发时的初始y轴坐标
    private float mInitialDownY;
    //是否在响应拖动标志位
    private boolean mIsBeingDragged;
    //顶部指示球是否在响应拖动
    private boolean mIsBeingDraggedTop;
    //底部指示球是否在响应拖动
    private boolean mIsBeingDraggedBottom;

    private int mActivePointerId = INVALID_POINTER;
    /*指示球是否需要在滑动过程中展示缩放动画，默认不需要，若未true，指示球在被拖动时会随着靠近最大拖动阈值而逐渐变大，
    直到放大到scaleX/Y = 1*/
    boolean mScale;

    // Target is returning to its start offset because it was cancelled or a
    // refresh was triggered.
    private boolean mReturningToStart;
    //所有指示球的位移动画(如松手回弹到初始位置，松手回弹到悬停位置)的默认减速差值器
    private final DecelerateInterpolator mDecelerateInterpolator;
    private static final int[] LAYOUT_ATTRS = new int[]{
            android.R.attr.enabled
    };
    //顶部刷新指示器View
    CircleImageView mTopCircleView;
    //底部刷新指示器View
    CircleImageView mBottomCircleView;

    private int mCircleViewIndex = -1;

    //顶部指示球开始位移到悬垂位置动画时的起始y轴坐标
    protected int mTopFrom;

    //底部指示球开始位移到悬垂位置动画时的起始y轴坐标
    protected int mBottomFrom;

    //顶部指示球开始缩小动画时的当前透明度
    float mTopStartingScale;

    //底部指示球开始缩小动画时的当前透明度
    float mBottomStartingScale;
    //顶部指示器y轴原始偏移量(相对于y轴原点)
    protected int mOriginalOffsetTop;

    //底部指示器y轴原始偏移量(相对于y轴原点)
    protected int mOriginalOffsetBottom;

    //顶部指示球的最大滑动偏移量(即悬垂位置距离容器顶部的距离)
    int mTopSpinnerOffsetEnd;

    //底部指示球的最大滑动偏移量(即悬垂位置距离容器底部的距离)
    int mBottomSpinnerOffsetEnd;

    int mCustomSlingshotDistance;
    //顶部刷新球内容Drawable
    CircularProgressDrawable mTopProgress;
    //底部刷新球内容Drawable
    CircularProgressDrawable mBottomProgress;
    //顶部指示球的放大动画
    private Animation mTopScaleAnimation;
    //底部指示球的放大动画
    private Animation mBottomScaleAnimation;
    //顶部刷新球缩小动画
    private Animation mTopScaleDownAnimation;
    //底部刷新球缩小动画
    private Animation mBottomScaleDownAnimation;
    //顶部指示球透明度变为初始值动画
    private Animation mTopAlphaStartAnimation;
    //底部指示球透明度变为初始值动画
    private Animation mBottomAlphaStartAnimation;
    //顶部指示球透明度变为最大动画
    private Animation mTopAlphaMaxAnimation;
    //底部指示球透明度变为最大动画
    private Animation mBottomAlphaMaxAnimation;
    //顶部指示球回弹到初始化位置且缩小动画
    private Animation mTopScaleDownToStartAnimation;
    //底部指示球回弹到初始化位置且缩小动画
    private Animation mBottomScaleDownToStartAnimation;
    //是否需要回调onRefresh监听标志位
    boolean mNotify;
    //指示球的直径
    private int mCircleDiameter;
    //是否由外部设置了自定义的开始位置，默认为false
    boolean mUsingCustomStart;
    //子View是否能向上滑动监听，由外部实现
    private OnChildScrollUpCallback mChildScrollUpCallback;

    //顶部刷新球刷新移动到指定悬垂位置的动画监听器
    private AnimationListener mTopRefreshListener = new AnimationListener() {
        @Override
        public void onAnimationStart(Animation animation) {
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
        }

        @Override
        public void onAnimationEnd(Animation animation) {
            //当顶部指示球已经移动到悬垂位置后
            if (mTopRefreshing) {
                //将指示球的透明度设满，让其完全显示
                mTopProgress.setAlpha(MAX_ALPHA);
                //开始指示球内部圆环的旋转动画，表示当前正在刷新中
                mTopProgress.start();
                if (mNotify) {
                    //判断是否需要通知
                    if (mTopListener != null) {
                        //若设置了顶部指示球刷新监听，回调onRefresh
                        mTopListener.onRefresh();
                    }
                }
                mCurrentTargetOffsetTop = mTopCircleView.getTop();
            } else {
                //若刷新状态为false，则说明顶部刷新球需要消失，触发顶部刷新球的reset逻辑
                resetTop();
            }
        }
    };

    //底部指示球滑动到悬停位置的动画监听器
    private AnimationListener mBottomToCorrectAnimationListener = new AnimationListener() {
        @Override
        public void onAnimationStart(Animation animation) {
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
        }

        @Override
        public void onAnimationEnd(Animation animation) {
            //当动画完成时，此时底部指示球已经处在悬停位置，接下来可以开始不停旋转了
            if (mBottomRefreshing) {
                // Make sure the progress view is fully visible
                mBottomProgress.setAlpha(MAX_ALPHA);
                //开始让底部指示球不停转动
                mBottomProgress.start();
                if (mNotify) {
                    //触发底部指示球开始刷新回调
                    if (mBottomListener != null) {
                        mBottomListener.onRefresh();
                    }
                }
                mCurrentTargetOffsetBottom = mBottomCircleView.getTop();
            } else {
                //若刷新状态为false，则说明顶部刷新球需要消失，触发顶部刷新球的reset逻辑
                resetBottom();
            }
        }
    };

    /**
     * 重置顶部指示球的状态
     */
    void resetTop() {
        //清空指示球的动画
        mTopCircleView.clearAnimation();
        //内部圆环停止旋转（停止其动画）
        mTopProgress.stop();
        mTopCircleView.setVisibility(View.GONE);
        setTopColorViewAlpha(MAX_ALPHA);
        // Return the circle to its start position
        if (mScale) {
            setTopViewScaleProgress(0 /* animation complete and view is hidden */);
        } else {
            //将顶部指示器移动到初始位置，从当前位置移动到初始位置所需要的偏移量为 原始位置-当前偏移量
            setTopCircleViewTargetOffsetTopAndBottom(mOriginalOffsetTop - mCurrentTargetOffsetTop);
        }
        //重置当前偏移量，为移动后顶部指示球的顶点y轴坐标
        mCurrentTargetOffsetTop = mTopCircleView.getTop();
    }

    /**
     * 重置顶部指示球的状态
     */
    void resetBottom() {
        mBottomCircleView.clearAnimation();
        mBottomProgress.stop();
        mBottomCircleView.setVisibility(View.GONE);
        setBottomColorViewAlpha(MAX_ALPHA);
        // Return the circle to its start position
        if (mScale) {
            setBottomViewScaleProgress(0 /* animation complete and view is hidden */);
        } else {
            //将底部指示器移动到初始位置，从当前位置移动到初始位置所需要的偏移量为 原始偏移量-当前偏移量
            setBottomCircleViewTargetOffsetTopAndBottom(mOriginalOffsetBottom - mCurrentTargetOffsetBottom);
        }
        //重置当前偏移量，为移动后顶部指示球的顶点y轴坐标
        mCurrentTargetOffsetBottom = mBottomCircleView.getTop();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) {
            resetTop();
            resetBottom();
        }
    }

    /**
     * 注意!因为SwipeRefreshLayout是有动画的，所以必须在onDetachedFromWindow中清空、停止动画，防止内存泄露
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        //重置顶部指示球
        resetTop();
        //重置底部指示球
        resetBottom();
    }

    /**
     * 设置顶部指示球的透明度
     * @param targetAlpha 目标透明度
     */
    private void setTopColorViewAlpha(int targetAlpha) {
        mTopCircleView.getBackground().setAlpha(targetAlpha);
        mTopProgress.setAlpha(targetAlpha);
    }

    /**
     * 设置底部指示球的透明度
     * @param targetAlpha 目标透明度
     */
    private void setBottomColorViewAlpha(int targetAlpha) {
        mBottomCircleView.getBackground().setAlpha(targetAlpha);
        mBottomProgress.setAlpha(targetAlpha);
    }

    /**
     * 设置指示球的纯色背景
     */
    public void setProgressBackgroundColorSchemeResource(@ColorRes int colorRes) {
        setProgressBackgroundColorSchemeColor(ContextCompat.getColor(getContext(), colorRes));
    }

    /**
     * Set the background color of the progress spinner disc.
     *
     * @param color
     */
    public void setProgressBackgroundColorSchemeColor(@ColorInt int color) {
        mTopCircleView.setBackgroundColor(color);
        mBottomCircleView.setBackgroundColor(color);
    }

    /**
     * 设置指示球内部旋转条的颜色，可传入一个颜色资源数组
     */
    public void setColorSchemeResources(@ColorRes int... colorResIds) {
        final Context context = getContext();
        int[] colorRes = new int[colorResIds.length];
        for (int i = 0; i < colorResIds.length; i++) {
            colorRes[i] = ContextCompat.getColor(context, colorResIds[i]);
        }
        setColorSchemeColors(colorRes);
    }

    /**
     * Set the colors used in the progress animation. The first
     * color will also be the color of the bar that grows in response to a user
     * swipe gesture.
     *
     * @param colors
     */
    public void setColorSchemeColors(@ColorInt int... colors) {
        ensureTarget();
        mTopProgress.setColorSchemeColors(colors);
        mBottomProgress.setColorSchemeColors(colors);
    }

    /**
     * 返回顶部指示球是否正在刷新
     */
    public boolean isTopRefreshing() {
        return mTopRefreshing;
    }

    /**
     * 返回底部指示球是否正在刷新
     */
    public boolean isBottomRefreshing() {
        return mBottomRefreshing;
    }

    /**
     * 设置指示球消失时是否附带缩小动画
     */
    public void setProgressViewScale(boolean scale) {
        mScale = scale;
        resetTop();
        resetBottom();
    }

    /**
     * @return The offset in pixels from the top of this view at which the progress spinner should
     * appear.
     */
    public int getProgressViewStartOffset() {
        return mOriginalOffsetTop;
    }

    /**
     * @return The offset in pixels from the top of this view at which the progress spinner should
     * come to rest after a successful swipe gesture.
     */
    public int getProgressViewEndOffset() {
        return mTopSpinnerOffsetEnd;
    }

    /**
     * 设置顶部指示球的刷新阈值位置（同时也是悬垂刷新位置）
     *
     * @param end   The offset in pixels from the top of this view at which the
     *              progress spinner should come to rest after a successful swipe
     *              gesture.
     */
    public void setTopProgressViewEndTarget(int end) {
        mTopSpinnerOffsetEnd = end;
        mTopCircleView.invalidate();
    }

    /**
     * 设置底部指示球的刷新阈值位置（同时也是悬垂刷新位置）
     *
     * @param end   The offset in pixels from the top of this view at which the
     *              progress spinner should come to rest after a successful swipe
     *              gesture.
     */
    public void setBottomProgressViewEndTarget(int end) {
        mBottomSpinnerOffsetEnd = end;
        mBottomCircleView.invalidate();
    }

    /**
     * 设置额外的拉伸距离，设置的越大，指示球可以被拉伸的距离最大滑动阈值越远.
     *
     * @param slingshotDistance The distance in pixels that the refresh indicator can be pulled
     *                          beyond its resting position.
     */
    public void setSlingshotDistance(@Px int slingshotDistance) {
        mCustomSlingshotDistance = slingshotDistance;
    }

    /**
     * 设置指示球的大小，只有两种大小可选，一种是DEFAULT、另一种是LARGE
     */
    public void setSize(int size) {
        if (size != CircularProgressDrawable.LARGE && size != CircularProgressDrawable.DEFAULT) {
            return;
        }
        final DisplayMetrics metrics = getResources().getDisplayMetrics();
        if (size == CircularProgressDrawable.LARGE) {
            mCircleDiameter = (int) (CIRCLE_DIAMETER_LARGE * metrics.density);
        } else {
            mCircleDiameter = (int) (CIRCLE_DIAMETER * metrics.density);
        }
        // force the bounds of the progress circle inside the circle view to
        // update by setting it to null before updating its size and then
        // re-setting it
        mTopCircleView.setImageDrawable(null);
        mTopProgress.setStyle(size);
        mTopCircleView.setImageDrawable(mTopProgress);

        mBottomCircleView.setImageDrawable(null);
        mBottomProgress.setStyle(size);
        mBottomCircleView.setImageDrawable(mBottomProgress);
    }

    /**
     * 设置顶部指示球刷新监听
     */
    public void setOnTopRefreshListener(OnRefreshListener listener) {
        mTopListener = listener;
    }

    /**
     * 设置底部指示球刷新监听
     */
    public void setOnBottomRefreshListener(OnRefreshListener listener) {
        mBottomListener = listener;
    }

    /**
     * Simple constructor to use when creating a SwipeRefreshLayout from code.
     *
     * @param context
     */
    public DuplexSwipeRefreshLayout(@NonNull Context context) {
        this(context, null);
    }

    /**
     * Constructor that is called when inflating SwipeRefreshLayout from XML.
     *
     * @param context
     * @param attrs
     */
    public DuplexSwipeRefreshLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        mMediumAnimationDuration = getResources().getInteger(
                android.R.integer.config_mediumAnimTime);

        setWillNotDraw(false);
        mDecelerateInterpolator = new DecelerateInterpolator(DECELERATE_INTERPOLATION_FACTOR);

        final DisplayMetrics metrics = getResources().getDisplayMetrics();
        mCircleDiameter = (int) (CIRCLE_DIAMETER * metrics.density);
        //初始化顶部和底部的指示球View
        createProgressView();
        setChildrenDrawingOrderEnabled(true);
        // the absolute offset has to take into account that the circle starts at an offset
        mTopSpinnerOffsetEnd = (int) (DEFAULT_CIRCLE_TARGET * metrics.density);
        mTotalDragDistance = mTopSpinnerOffsetEnd;
        mBottomSpinnerOffsetEnd = mTopSpinnerOffsetEnd;
        mNestedScrollingParentHelper = new NestedScrollingParentHelper(this);

        mNestedScrollingChildHelper = new NestedScrollingChildHelper(this);
        setNestedScrollingEnabled(true);

        mOriginalOffsetTop = mCurrentTargetOffsetTop = -mCircleDiameter;
        moveToStart(1.0f);
        //moveToEnd(1.0f);

        final TypedArray a = context.obtainStyledAttributes(attrs, LAYOUT_ATTRS);
        setEnabled(a.getBoolean(0, true));
        a.recycle();
    }

    @Override
    protected int getChildDrawingOrder(int childCount, int i) {
        if (mCircleViewIndex < 0) {
            return i;
        } else if (i == childCount - 1) {
            // Draw the selected child last
            return mCircleViewIndex;
        } else if (i >= mCircleViewIndex) {
            // Move the children after the selected child earlier one
            return i + 1;
        } else {
            // Keep the children before the selected child the same
            return i;
        }
    }

    /**
     * 初始化顶部、底部的指示球，并将其添加到SwipeLayout中
     */
    private void createProgressView() {
        mTopCircleView = new CircleImageView(getContext(), CIRCLE_BG_LIGHT);
        mTopProgress = new CircularProgressDrawable(getContext());
        mTopProgress.setStyle(CircularProgressDrawable.DEFAULT);
        mTopCircleView.setImageDrawable(mTopProgress);
        mTopCircleView.setVisibility(View.GONE);
        addView(mTopCircleView);

        mBottomCircleView = new CircleImageView(getContext(), CIRCLE_BG_LIGHT);
        mBottomProgress = new CircularProgressDrawable(getContext());
        mBottomProgress.setStyle(CircularProgressDrawable.DEFAULT);
        mBottomCircleView.setImageDrawable(mBottomProgress);
        mBottomCircleView.setVisibility(View.GONE);
        addView(mBottomCircleView);
    }

    /**
     * 设置顶部指示器的刷新状态
     */
    public void setTopRefreshing(boolean refreshing) {
        if (refreshing && mTopRefreshing != refreshing) {
            // scale and show
            mTopRefreshing = refreshing;
            int endTarget = 0;
            if (!mUsingCustomStart) {
                endTarget = mTopSpinnerOffsetEnd + mOriginalOffsetTop;
            } else {
                endTarget = mTopSpinnerOffsetEnd;
            }
            setTopCircleViewTargetOffsetTopAndBottom(endTarget - mCurrentTargetOffsetTop);
            mNotify = false;
            startTopScaleUpAnimation(mTopRefreshListener);
        } else {
            setTopRefreshing(refreshing, false /* notify */);
        }
    }

    /**
     * 设置底部指示器的刷新状态
     */
    public void setBottomRefreshing(boolean refreshing) {
        if (refreshing && mBottomRefreshing != refreshing) {
            // scale and show
            mBottomRefreshing = refreshing;
            int endTarget = 0;
            if (mUsingCustomStart) {
                endTarget = mTopSpinnerOffsetEnd + mOriginalOffsetTop;
            } else {
                endTarget = mOriginalOffsetBottom - mBottomSpinnerOffsetEnd;
            }
            setBottomCircleViewTargetOffsetTopAndBottom(endTarget - mCurrentTargetOffsetBottom);
            mNotify = false;
            startBottomScaleUpAnimation(mBottomToCorrectAnimationListener);
        } else {
            setBottomRefreshing(refreshing, false /* notify */);
        }
    }

    private void startTopScaleUpAnimation(AnimationListener listener) {
        mTopCircleView.setVisibility(View.VISIBLE);
        mTopProgress.setAlpha(MAX_ALPHA);
        mTopScaleAnimation = new Animation() {
            @Override
            public void applyTransformation(float interpolatedTime, Transformation t) {
                setTopViewScaleProgress(interpolatedTime);
            }
        };
        mTopScaleAnimation.setDuration(mMediumAnimationDuration);
        if (listener != null) {
            mTopCircleView.setAnimationListener(listener);
        }
        mTopCircleView.clearAnimation();
        mTopCircleView.startAnimation(mTopScaleAnimation);
    }

    /**
     * 开始底部指示球的放大动画
     *
     * @param listener 放大动画的监听
     */
    private void startBottomScaleUpAnimation(AnimationListener listener) {
        mBottomCircleView.setVisibility(View.VISIBLE);
        mBottomProgress.setAlpha(MAX_ALPHA);
        mBottomScaleAnimation = new Animation() {
            @Override
            public void applyTransformation(float interpolatedTime, Transformation t) {
                setBottomViewScaleProgress(interpolatedTime);
            }
        };
        mBottomScaleAnimation.setDuration(mMediumAnimationDuration);
        if (listener != null) {
            mBottomCircleView.setAnimationListener(listener);
        }
        mBottomCircleView.clearAnimation();
        mBottomCircleView.startAnimation(mBottomScaleAnimation);
    }

    /**
     * Pre API 11, this does an alpha animation.
     *
     * @param progress
     */
    void setTopViewScaleProgress(float progress) {
        mTopCircleView.setScaleX(progress);
        mTopCircleView.setScaleY(progress);
    }

    void setBottomViewScaleProgress(float progress) {
        mBottomCircleView.setScaleX(progress);
        mBottomCircleView.setScaleY(progress);
    }

    private void setTopRefreshing(boolean refreshing, final boolean notify) {
        if (mTopRefreshing != refreshing) {
            mNotify = notify;
            ensureTarget();
            mTopRefreshing = refreshing;
            if (mTopRefreshing) {
                animateTopOffsetToCorrectPosition(mCurrentTargetOffsetTop, mTopRefreshListener);
            } else {
                startTopScaleDownAnimation(mTopRefreshListener);
            }
        }
    }


    /**
     * 设置底部指示球是否处在刷新状态
     *
     * @param refreshing 是否处在刷新状态
     * @param notify     刷新时是否需要触发onRefresh回调
     */
    private void setBottomRefreshing(boolean refreshing, final boolean notify) {
        if (mBottomRefreshing != refreshing) {
            mNotify = notify;
            ensureTarget();
            mBottomRefreshing = refreshing;
            if (mBottomRefreshing) {
                //若需要刷新，则将底部指示球弹回悬垂位置，开始旋转
                animateBottomOffsetToCorrectPosition(mCurrentTargetOffsetBottom, mBottomToCorrectAnimationListener);
            } else {
                //若不需要刷新，则将底部指示球进行缩放动画，使其消失，并重置底部指示球的状态
                startBottomScaleDownAnimation(mBottomToCorrectAnimationListener);
            }
        }
    }

    //开始顶部指示球的缩小动画
    void startTopScaleDownAnimation(AnimationListener listener) {
        mTopScaleDownAnimation = new Animation() {
            @Override
            public void applyTransformation(float interpolatedTime, Transformation t) {
                setTopViewScaleProgress(1 - interpolatedTime);
            }
        };
        mTopScaleDownAnimation.setDuration(SCALE_DOWN_DURATION);
        mTopCircleView.setAnimationListener(listener);
        mTopCircleView.clearAnimation();
        mTopCircleView.startAnimation(mTopScaleDownAnimation);
    }

    //开始底部指示球的缩小动画
    void startBottomScaleDownAnimation(AnimationListener listener) {
        mBottomScaleDownAnimation = new Animation() {
            @Override
            public void applyTransformation(float interpolatedTime, Transformation t) {
                setBottomViewScaleProgress(1 - interpolatedTime);
            }
        };
        mBottomScaleDownAnimation.setDuration(SCALE_DOWN_DURATION);
        mBottomCircleView.setAnimationListener(listener);
        mBottomCircleView.clearAnimation();
        mBottomCircleView.startAnimation(mBottomScaleDownAnimation);
    }

    //开始顶部指示球透明度变化为默认值动画（半透明）
    private void startTopProgressAlphaStartAnimation() {
        mTopAlphaStartAnimation = startTopAlphaAnimation(mTopProgress.getAlpha(), STARTING_PROGRESS_ALPHA);
    }

    //开始底部指示球透明度变化为默认值动画（半透明）
    private void startBottomProgressAlphaStartAnimation() {
        mBottomAlphaStartAnimation = startBottomAlphaAnimation(mBottomProgress.getAlpha(), STARTING_PROGRESS_ALPHA);
    }

    //开始顶部指示球透明度全满动画（完全显式）
    private void startTopProgressAlphaMaxAnimation() {
        mTopAlphaMaxAnimation = startTopAlphaAnimation(mTopProgress.getAlpha(), MAX_ALPHA);
    }

    //开始底部指示球透明度全满动画（完全显式）
    private void startBottomProgressAlphaMaxAnimation() {
        mBottomAlphaMaxAnimation = startBottomAlphaAnimation(mBottomProgress.getAlpha(), MAX_ALPHA);
    }

    private Animation startTopAlphaAnimation(final int startingAlpha, final int endingAlpha) {
        Animation alpha = new Animation() {
            @Override
            public void applyTransformation(float interpolatedTime, Transformation t) {
                mTopProgress.setAlpha(
                        (int) (startingAlpha + ((endingAlpha - startingAlpha) * interpolatedTime)));
            }
        };
        alpha.setDuration(ALPHA_ANIMATION_DURATION);
        // Clear out the previous animation listeners.
        mTopCircleView.setAnimationListener(null);
        mTopCircleView.clearAnimation();
        mTopCircleView.startAnimation(alpha);
        return alpha;
    }

    /**
     * 这里使用了动画来改变拖动过程中底部指示球的透明度，相比直接根据拖动百分比改变透明度，动画的方式显得更柔和和平滑
     *
     * @param startingAlpha 起始透明度
     * @param endingAlpha   目标透明度
     * @return 动画对象
     */
    private Animation startBottomAlphaAnimation(final int startingAlpha, final int endingAlpha) {
        Animation alpha = new Animation() {
            @Override
            public void applyTransformation(float interpolatedTime, Transformation t) {
                mBottomProgress.setAlpha(
                        (int) (startingAlpha + ((endingAlpha - startingAlpha) * interpolatedTime)));
            }
        };
        //指示球的透明度动画的默认执行时间为300ms，动画过渡效果很快，如果想要动画更平滑，可以适当增大duration
        alpha.setDuration(ALPHA_ANIMATION_DURATION);
        // Clear out the previous animation listeners.
        mBottomCircleView.setAnimationListener(null);
        mBottomCircleView.clearAnimation();
        mBottomCircleView.startAnimation(alpha);
        return alpha;
    }

    private void ensureTarget() {
        // Don't bother getting the parent height if the parent hasn't been laid
        // out yet.
        if (mTarget == null) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (!child.equals(mTopCircleView) && !child.equals(mBottomCircleView)) {
                    mTarget = child;
                    break;
                }
            }
        }
    }

    /**
     * Set the distance to trigger a sync in dips
     *
     * @param distance
     */
    public void setDistanceToTriggerSync(int distance) {
        mTotalDragDistance = distance;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int width = getMeasuredWidth();
        final int height = getMeasuredHeight();
        if (getChildCount() == 0) {
            return;
        }
        if (mTarget == null) {
            ensureTarget();
        }
        if (mTarget == null) {
            return;
        }
        final View child = mTarget;
        final int childLeft = getPaddingLeft();
        final int childTop = getPaddingTop();
        final int childWidth = width - getPaddingLeft() - getPaddingRight();
        final int childHeight = height - getPaddingTop() - getPaddingBottom();
        child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
        int circleWidth = mTopCircleView.getMeasuredWidth();
        int circleHeight = mTopCircleView.getMeasuredHeight();
        if (mOriginalOffsetBottom == 0) {
            mOriginalOffsetBottom = mCurrentTargetOffsetBottom = height;
        }
        //将顶部指示球布局在屏幕顶部
        mTopCircleView.layout((width / 2 - circleWidth / 2), mCurrentTargetOffsetTop,
                (width / 2 + circleWidth / 2), mCurrentTargetOffsetTop + circleHeight);

        //将底部指示球布局在屏幕底部
        mBottomCircleView.layout((width / 2 - circleWidth / 2), mCurrentTargetOffsetBottom,
                (width / 2 + circleWidth / 2), mCurrentTargetOffsetBottom + circleHeight);
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (mTarget == null) {
            ensureTarget();
        }
        if (mTarget == null) {
            return;
        }
        mTarget.measure(MeasureSpec.makeMeasureSpec(
                getMeasuredWidth() - getPaddingLeft() - getPaddingRight(),
                MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(
                getMeasuredHeight() - getPaddingTop() - getPaddingBottom(), MeasureSpec.EXACTLY));
        mTopCircleView.measure(MeasureSpec.makeMeasureSpec(mCircleDiameter, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(mCircleDiameter, MeasureSpec.EXACTLY));
        mCircleViewIndex = -1;
        // Get the index of the circleview.
        for (int index = 0; index < getChildCount(); index++) {
            if (getChildAt(index) == mTopCircleView) {
                mCircleViewIndex = index;
                break;
            }
        }
    }

    /**
     * Get the diameter of the progress circle that is displayed as part of the
     * swipe to refresh layout.
     *
     * @return Diameter in pixels of the progress circle view.
     */
    public int getProgressCircleDiameter() {
        return mCircleDiameter;
    }

    /**
     * @return Whether it is possible for the child view of this layout to
     * scroll up. Override this if the child view is a custom view.
     */
    public boolean canChildScrollUp() {
        if (mChildScrollUpCallback != null) {
            return mChildScrollUpCallback.canChildScrollUp(this, mTarget);
        }
        if (mTarget instanceof ListView) {
            return ListViewCompat.canScrollList((ListView) mTarget, -1);
        }
        return mTarget.canScrollVertically(-1);
    }

    /**
     * Set a callback to override {@link androidx.swiperefreshlayout.widget.SwipeRefreshLayout#canChildScrollUp()} method. Non-null
     * callback will return the value provided by the callback and ignore all internal logic.
     *
     * @param callback Callback that should be called when canChildScrollUp() is called.
     */
    public void setOnChildScrollUpCallback(@Nullable OnChildScrollUpCallback callback) {
        mChildScrollUpCallback = callback;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        /*保证mTarget的值，mTarget为SRL内部需要响应刷新事件的View
        （注意只能有一个子View，only support one direct child）*/
        ensureTarget();

        final int action = ev.getActionMasked();
        int pointerIndex;

        if (mReturningToStart && action == MotionEvent.ACTION_DOWN) {
            mReturningToStart = false;
        }

        if (!isEnabled() || mReturningToStart
                //|| canChildScrollUp()
                || mNestedScrollInProgress) {
            // Fail fast if we're not in a state where a swipe is possible
            return false;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (!mTopRefreshing) {
                    //若顶部指示球不处在刷新状态，手指落下时，将顶部指示球移动到初始位置上，准备响应后续的移动事件
                    setTopCircleViewTargetOffsetTopAndBottom(0);
                }
                if (!mBottomRefreshing) {
                    setBottomCircleViewTargetOffsetTopAndBottom(mOriginalOffsetBottom - mBottomCircleView.getTop());
                }
                mActivePointerId = ev.getPointerId(0);
                mIsBeingDragged = false;

                pointerIndex = ev.findPointerIndex(mActivePointerId);
                if (pointerIndex < 0) {
                    return false;
                }
                //手指落下时，记录落下的y轴位置
                mInitialDownY = ev.getY(pointerIndex);
                break;

            case MotionEvent.ACTION_MOVE:
                if (mActivePointerId == INVALID_POINTER) {
                    Log.e(LOG_TAG, "Got ACTION_MOVE event but don't have an active pointer id.");
                    return false;
                }
                pointerIndex = ev.findPointerIndex(mActivePointerId);
                if (pointerIndex < 0) {
                    return false;
                }
                final float y = ev.getY(pointerIndex);
                //判断是否需要拦截触摸事件
                startDragging(y);
                break;

            case MotionEvent.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mIsBeingDragged = false;
                mActivePointerId = INVALID_POINTER;
                break;
        }

        return mIsBeingDragged;
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean b) {
        // if this is a List < L or another view that doesn't support nested
        // scrolling, ignore this request so that the vertical scroll event
        // isn't stolen
        if ((android.os.Build.VERSION.SDK_INT < 21 && mTarget instanceof AbsListView)
                || (mTarget != null && !ViewCompat.isNestedScrollingEnabled(mTarget))) {
            // Nope.
        } else {
            super.requestDisallowInterceptTouchEvent(b);
        }
    }

    //todo 处理嵌套滑动监听，兼容底部指示球的上拉功能
    @Override
    public boolean onStartNestedScroll(View child, View target, int nestedScrollAxes) {
        return isEnabled() && !mReturningToStart
                //&& !mTopRefreshing
                && (nestedScrollAxes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
    }

    @Override
    public void onNestedScrollAccepted(View child, View target, int axes) {
        // Reset the counter of how much leftover scroll needs to be consumed.
        mNestedScrollingParentHelper.onNestedScrollAccepted(child, target, axes);
        // Dispatch up to the nested parent
        startNestedScroll(axes & ViewCompat.SCROLL_AXIS_VERTICAL);
        mTotalUnconsumed = 0;
        mNestedScrollInProgress = true;
    }

    /**
     * 在子View消费滑动事件之前，会提前回调自身的消费动作给嵌套的ScrollParent
     *
     * @param target   内部触发嵌套滑动事件的NestedScrollChild
     * @param dx       子view还未消费的横向滑动偏移量
     * @param dy       子view还未消费的纵向滑动偏移量
     * @param consumed 反向输入参数，子View创建该对象，然后传入函数中，父容器在接受到之后为其前两个元素赋值，含义
     *                 是consumed[0]表示父容器将消费的横向滑动偏移量，最大不能超过dx，consumed[1]表示父容器将要消费的
     *                 纵向滑动偏移量，最大不能超过dy
     */
    @Override
    public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {
        if (mTotalUnconsumed > 0) {
            //若mTotalUnconsumed大于0，表示当前正处在嵌套滑动中
            if (dy > 0) {
                //若当前嵌套滑动行为是向上滑动，需要判断顶部指示球是否已经在响应拖动了
                if (mTopViewNestedScrollDragging) {
                    /*如果顶部指示球已经在响应嵌套滑动，那么此时向上滑动的嵌套滑动必须做出特殊处理，需要由Swipe
                    完全消费掉向上的滑动行为，因为若不全部消费掉，内部的View例如RecycleView就会进行列表的滚动，这与设计不符，我们
                    希望在顶部球消失之前，内部View都不要进行任何的滚动行为*/
                    if (dy > mTotalUnconsumed) {
                        /*若mTotalUnconsumed已经小于dy，此时说明顶部指示球已经马上就要滑动到顶部消失了，此时仅需要消耗它和dy的差值
                        即可，然后此时可以将mTotalUnconsumed置为0了，因为已经不需要移动顶部指示球了，子View该开始响应上拉滚动行为了*/
                        consumed[1] = dy - (int) mTotalUnconsumed;
                        mTotalUnconsumed = 0;
                    } else {
                        //否则就将mTotalUnconsumed的值减去dy，然后消费掉所有dy，表示父容器要完全消费本次上滑行为，让子View先不要上滑
                        mTotalUnconsumed -= dy;
                        //将整个dy都完全消耗，并通过consumed告知子View
                        consumed[1] = dy;
                    }
                    //移动顶部指示球
                    moveTopSpinner(mTotalUnconsumed);
                }
            }
            if (dy < 0) {
                //若当前嵌套滑动行为是向下滑动，需要判断底部指示球是否已经在响应拖动了
                if (mBottomViewNestedScrollDragging) {
                    /*如果底部指示球已经在响应嵌套滑动，那么此时向下滑动的嵌套滑动必须做出特殊处理，需要由Swipe
                    完全消费掉向下的滑动行为，因为若不全部消费掉，内部的View例如RecycleView就会进行列表的滚动，这与设计不符，我们
                    希望在底部球消失之前，内部View都不要进行任何的向下滚动行为*/
                    int absDy = Math.abs(dy);
                    if (absDy > mTotalUnconsumed) {
                        /*若mTotalUnconsumed已经小于dy，此时说明顶部指示球已经马上就要滑动到顶部消失了，此时仅需要消耗它和dy的差值
                        即可，然后此时可以将mTotalUnconsumed置为0了，因为已经不需要移动顶部指示球了，子View该开始响应上拉滚动行为了*/
                        consumed[1] = dy + (int) mTotalUnconsumed;
                        mTotalUnconsumed = 0;
                    } else {
                        //否则就将mTotalUnconsumed的值减去dy，然后消费掉所有dy，表示父容器要完全消费本次上滑行为，让子View先不要上滑
                        mTotalUnconsumed += dy;
                        //将整个dy都完全消耗，并通过consumed告知子View
                        consumed[1] = dy;
                    }
                    //移动顶部指示球
                    moveBottomSpinner(mTotalUnconsumed);
                }
            }
        }
        Log.d("################", "onNestedPreScroll dy : " + dy);
        // If a client layout is using a custom start position for the circle
        // view, they mean to hide it again before scrolling the child view
        // If we get back to mTotalUnconsumed == 0 and there is more to go, hide
        // the circle so it isn't exposed if its blocking content is moved
        if (mUsingCustomStart && dy > 0 && mTotalUnconsumed == 0
                && Math.abs(dy - consumed[1]) > 0) {
            mTopCircleView.setVisibility(View.GONE);
        }

        // Now let our nested parent consume the leftovers
        final int[] parentConsumed = mParentScrollConsumed;
        if (dispatchNestedPreScroll(dx - consumed[0], dy - consumed[1], parentConsumed, null)) {
            consumed[0] += parentConsumed[0];
            consumed[1] += parentConsumed[1];
        }
    }

    @Override
    public int getNestedScrollAxes() {
        return mNestedScrollingParentHelper.getNestedScrollAxes();
    }

    @Override
    public void onStopNestedScroll(View target) {
        mNestedScrollingParentHelper.onStopNestedScroll(target);
        mNestedScrollInProgress = false;
        // Finish the spinner for nested scrolling if we ever consumed any
        // unconsumed nested scroll
        if (mTotalUnconsumed > 0) {
            if (mTopViewNestedScrollDragging) {
                finishTopSpinner(mTotalUnconsumed);
            }
            if (mBottomViewNestedScrollDragging) {
                finishBottomSpinner(mTotalUnconsumed);
            }
            mTotalUnconsumed = 0;
        }
        mTopViewNestedScrollDragging = false;
        mBottomViewNestedScrollDragging = false;
        // Dispatch up our nested parent
        stopNestedScroll();
    }

    /**
     * 响应子View的嵌套滑动核心函数
     *
     * @param target       发送嵌套滑动事件的子View
     * @param dxConsumed   子View在横向已经消费的滑动偏移量，顺负逆正，即该值大于0表示自左向右滑动，小于0表示从右向左滑动
     * @param dyConsumed   子View在纵向已经消费的滑动偏移量，顺负逆正，即该值大于0表示自下向上滑动，小于0表示从上向下滑动
     * @param dxUnconsumed 子View在横向还未消费的滑动偏移量
     * @param dyUnconsumed 子View在纵向还未消费的滑动偏移量
     */
    @Override
    public void onNestedScroll(final View target, final int dxConsumed, final int dyConsumed,
                               final int dxUnconsumed, final int dyUnconsumed) {
        // Dispatch up to the nested parent first
        dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed,
                mParentOffsetInWindow);

        /*当前需要响应的滑动量为子View在纵向未消费的滑动偏移量，例如内部view是RecycleView，那么当RecycleView被滑动到
        顶部时候，继续向下滑动RecycleView就不会再消费向下滑动的事件，而是通过dispatchNestedScroll将所有向下滑动的偏移量
        传递给ScrollParent，此时我们就可以根据需要展示顶部指示球，并响应嵌套滑动事件*/
        final int dy = dyUnconsumed + mParentOffsetInWindow[1];
        Log.i("################", "onNestedScroll dy : " + dy);
        Log.i("################", "onNestedScroll dyUnconsumed : " + dyUnconsumed);
        Log.i("################", "onNestedScroll dyConsumed : " + dyConsumed);
        if (dy < 0
            //&& !canChildScrollUp()
        ) {
            if (mTopRefreshing) return;
            //若dy小于0，说明子View有未消耗的向下滑动偏移量(即内部的列表控件已经滚动到顶了)，此时可以将滑动偏移量累加，并触发顶部指示球的移动
            mTotalUnconsumed += Math.abs(dy);
            Log.i("################", "onNestedScroll top scroll  : " + mTotalUnconsumed);
            mTopViewNestedScrollDragging = true;
            moveTopSpinner(mTotalUnconsumed);
        } else if (dy > 0) {
            if (mBottomRefreshing) return;
            //若dy大于0，说明子View有未消耗的向上滑动偏移量(即内部的列表控件已经滚动到底了)，此时可以将滑动偏移量累加，并触发底部部指示球的移动
            mTotalUnconsumed += dy;
            Log.i("################", "onNestedScroll bottom scroll : " + mTotalUnconsumed);
            mBottomViewNestedScrollDragging = true;
            moveBottomSpinner(mTotalUnconsumed);
        }
    }

    // NestedScrollingChild

    @Override
    public void setNestedScrollingEnabled(boolean enabled) {
        mNestedScrollingChildHelper.setNestedScrollingEnabled(enabled);
    }

    @Override
    public boolean isNestedScrollingEnabled() {
        return mNestedScrollingChildHelper.isNestedScrollingEnabled();
    }

    @Override
    public boolean startNestedScroll(int axes) {
        return mNestedScrollingChildHelper.startNestedScroll(axes);
    }

    @Override
    public void stopNestedScroll() {
        mNestedScrollingChildHelper.stopNestedScroll();
    }

    @Override
    public boolean hasNestedScrollingParent() {
        return mNestedScrollingChildHelper.hasNestedScrollingParent();
    }

    @Override
    public boolean dispatchNestedScroll(int dxConsumed, int dyConsumed, int dxUnconsumed,
                                        int dyUnconsumed, int[] offsetInWindow) {
        return mNestedScrollingChildHelper.dispatchNestedScroll(dxConsumed, dyConsumed,
                dxUnconsumed, dyUnconsumed, offsetInWindow);
    }

    @Override
    public boolean dispatchNestedPreScroll(int dx, int dy, int[] consumed, int[] offsetInWindow) {
        return mNestedScrollingChildHelper.dispatchNestedPreScroll(
                dx, dy, consumed, offsetInWindow);
    }

    @Override
    public boolean onNestedPreFling(View target, float velocityX,
                                    float velocityY) {
        return dispatchNestedPreFling(velocityX, velocityY);
    }

    @Override
    public boolean onNestedFling(View target, float velocityX, float velocityY,
                                 boolean consumed) {
        return dispatchNestedFling(velocityX, velocityY, consumed);
    }

    @Override
    public boolean dispatchNestedFling(float velocityX, float velocityY, boolean consumed) {
        return mNestedScrollingChildHelper.dispatchNestedFling(velocityX, velocityY, consumed);
    }

    @Override
    public boolean dispatchNestedPreFling(float velocityX, float velocityY) {
        return mNestedScrollingChildHelper.dispatchNestedPreFling(velocityX, velocityY);
    }

    private boolean isAnimationRunning(Animation animation) {
        return animation != null && animation.hasStarted() && !animation.hasEnded();
    }

    private void moveTopSpinner(float overscrollTop) {
        mTopProgress.setArrowEnabled(true);
        //原始拖动百分比，拖动距离 / 最大滑动距离
        float originalDragPercent = Math.abs(overscrollTop / mTotalDragDistance);
        //拖动百分比最大为1
        float dragPercent = Math.min(1f, Math.abs(originalDragPercent));
        float adjustedPercent = (float) Math.max(dragPercent - .4, 0) * 5 / 3;

        float extraOS = Math.abs(overscrollTop) - mTotalDragDistance;

        float slingshotDist = mCustomSlingshotDistance > 0
                ? mCustomSlingshotDistance
                : (mUsingCustomStart
                ? mTopSpinnerOffsetEnd - mOriginalOffsetTop
                : mTopSpinnerOffsetEnd);
        float tensionSlingshotPercent = Math.max(0, Math.min(extraOS, slingshotDist * 2)
                / slingshotDist);
        float tensionPercent = (float) ((tensionSlingshotPercent / 4) - Math.pow(
                (tensionSlingshotPercent / 4), 2)) * 2f;
        float extraMove = (slingshotDist) * tensionPercent * 2;

        int targetY = mOriginalOffsetTop + (int) ((slingshotDist * dragPercent) + extraMove);
        // where 1.0f is a full circle
        if (mTopCircleView.getVisibility() != View.VISIBLE) {
            mTopCircleView.setVisibility(View.VISIBLE);
        }
        if (!mScale) {
            mTopCircleView.setScaleX(1f);
            mTopCircleView.setScaleY(1f);
        }

        if (mScale) {
            setTopViewScaleProgress(Math.min(1f, overscrollTop / mTotalDragDistance));
        }
        if (overscrollTop < mTotalDragDistance) {
            if (mTopProgress.getAlpha() > STARTING_PROGRESS_ALPHA
                    && !isAnimationRunning(mTopAlphaStartAnimation)) {
                // Animate the alpha
                startTopProgressAlphaStartAnimation();
            }
        } else {
            if (mTopProgress.getAlpha() < MAX_ALPHA && !isAnimationRunning(mTopAlphaMaxAnimation)) {
                // Animate the alpha
                startTopProgressAlphaMaxAnimation();
            }
        }
        float strokeStart = adjustedPercent * .8f;
        mTopProgress.setStartEndTrim(0f, Math.min(MAX_PROGRESS_ANGLE, strokeStart));
        mTopProgress.setArrowScale(Math.min(1f, adjustedPercent));

        float rotation = (-0.25f + .4f * adjustedPercent + tensionPercent * 2) * .5f;
        mTopProgress.setProgressRotation(rotation);
        setTopCircleViewTargetOffsetTopAndBottom(targetY - mCurrentTargetOffsetTop);
    }

    private void moveBottomSpinner(float overscrollTop) {
        mBottomProgress.setArrowEnabled(true);
        //原始拖动百分比，拖动距离 / 最大滑动距离
        float originalDragPercent = Math.abs(overscrollTop / mTotalDragDistance);
        //拖动百分比最大为1
        float dragPercent = Math.min(1f, Math.abs(originalDragPercent));

        float adjustedPercent = (float) Math.max(dragPercent - .4, 0) * 5 / 3;
        //拖动距离与最大滑动距离的差值
        float extraOS = Math.abs(overscrollTop) - mTotalDragDistance;
        //指示球的悬挂距离（即松手后指示球即将停留的旋转位置），若未显式设置，默认为指示球的最大滑动距离
        float slingshotDist = mCustomSlingshotDistance > 0
                ? mCustomSlingshotDistance
                : (mUsingCustomStart
                ? mBottomSpinnerOffsetEnd - mOriginalOffsetTop
                : mBottomSpinnerOffsetEnd);
        //指示球的最大拉扯百分比，最大为2
        float tensionSlingshotPercent = Math.max(0, Math.min(extraOS, slingshotDist * 2)
                / slingshotDist);
        //拉扯百分比
        float tensionPercent = (float) ((tensionSlingshotPercent / 4) - Math.pow(
                (tensionSlingshotPercent / 4), 2)) * 2f;
        //额外移动距离，即超过最大滑动距离后的拉扯移动距离
        float extraMove = (slingshotDist) * tensionPercent * 2;
        //本次响应手势的目标Y轴位置
        int targetY = mOriginalOffsetBottom - (int) ((slingshotDist * dragPercent) + extraMove);
        //将底部指示球置为可见
        if (mBottomCircleView.getVisibility() != View.VISIBLE) {
            mBottomCircleView.setVisibility(View.VISIBLE);
        }
        if (!mScale) {
            mBottomCircleView.setScaleX(1f);
            mBottomCircleView.setScaleY(1f);
        }
        if (mScale) {
            //若设置了缩放标志位，则根据滑动距离和最大滑动阈值的比例对底部指示球进行缩放
            setBottomViewScaleProgress(Math.min(1f, Math.abs(overscrollTop / mTotalDragDistance)));
        }

        if (Math.abs(overscrollTop) < mTotalDragDistance) {
            //若滑动距离小于最大拖动阈值，且底部指示球的透明度大于默认透明度，且底部指示球并未处在透明度缩小动画中
            if (mBottomProgress.getAlpha() > STARTING_PROGRESS_ALPHA
                    && !isAnimationRunning(mBottomAlphaStartAnimation)) {
                //开始透明度变小动画，目的当指示球未达到刷新阈值位置时，透明度必须是偏小的
                startBottomProgressAlphaStartAnimation();
            }
        } else {
            /*若滑动距离大于最大拖动阈值，此时透明度需要拉满，目的是当指示球被滑动到超过刷新阈值位置时，
            需要通过透明度改变提示用户已经达到可刷新位置*/
            if (mBottomProgress.getAlpha() < MAX_ALPHA && !isAnimationRunning(mBottomAlphaMaxAnimation)) {
                //如果透明度没有到最大，且并没有透明度变大动画正在执行，那么就执行透明度变大动画
                startBottomProgressAlphaMaxAnimation();
            }
        }
        float strokeStart = adjustedPercent * .8f;
        mBottomProgress.setStartEndTrim(0f, Math.min(MAX_PROGRESS_ANGLE, strokeStart));
        mBottomProgress.setArrowScale(Math.min(1f, adjustedPercent));

        float rotation = (-0.25f + .4f * adjustedPercent + tensionPercent * 2) * .5f;
        mBottomProgress.setProgressRotation(rotation);
        setBottomCircleViewTargetOffsetTopAndBottom(targetY - mCurrentTargetOffsetBottom);
    }

    private void finishTopSpinner(float overscrollTop) {
        if (overscrollTop > mTotalDragDistance) {
            setTopRefreshing(true, true /* notify */);
        } else {
            // cancel refresh
            mTopRefreshing = false;
            mTopProgress.setStartEndTrim(0f, 0f);
            AnimationListener listener = null;
            if (!mScale) {
                listener = new AnimationListener() {

                    @Override
                    public void onAnimationStart(Animation animation) {
                    }

                    @Override
                    public void onAnimationEnd(Animation animation) {
                        if (!mScale) {
                            startTopScaleDownAnimation(null);
                        }
                    }

                    @Override
                    public void onAnimationRepeat(Animation animation) {
                    }

                };
            }
            animateTopOffsetToStartPosition(mCurrentTargetOffsetTop, listener);
            mTopProgress.setArrowEnabled(false);
        }
    }

    //手指抬起后，根据滑动距离（滑动距离*滑动比例.5f）决定是否要触发底部指示球持续刷新逻辑
    private void finishBottomSpinner(float overscrollTop) {
        if (overscrollTop > mTotalDragDistance) {
            //若滑动距离大于拖拽距离阈值，达到可触发刷新逻辑位置，先让底部指示球滑动悬垂位置，再持续旋转表示刷新中
            setBottomRefreshing(true, true /* notify */);
        } else {
            //若滑动距离小于拖拽距离阈值，则认为未达到刷新位置，将底部指示球弹回底部
            mBottomRefreshing = false;
            mBottomProgress.setStartEndTrim(0f, 0f);
            AnimationListener listener = null;
            if (!mScale) {
                listener = new AnimationListener() {

                    @Override
                    public void onAnimationStart(Animation animation) {
                    }

                    @Override
                    public void onAnimationEnd(Animation animation) {
                        if (!mScale) {
                            startBottomScaleDownAnimation(null);
                        }
                    }

                    @Override
                    public void onAnimationRepeat(Animation animation) {
                    }

                };
            }
            animateBottomOffsetToStartPosition(mCurrentTargetOffsetBottom, listener);
            mBottomProgress.setArrowEnabled(false);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        final int action = ev.getActionMasked();
        int pointerIndex = -1;

        if (mReturningToStart && action == MotionEvent.ACTION_DOWN) {
            mReturningToStart = false;
        }

        if (!isEnabled() || mReturningToStart
                //|| canChildScrollUp()
                || mNestedScrollInProgress) {
            // Fail fast if we're not in a state where a swipe is possible
            return false;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mActivePointerId = ev.getPointerId(0);
                mIsBeingDragged = false;
                break;

            case MotionEvent.ACTION_MOVE: {
                pointerIndex = ev.findPointerIndex(mActivePointerId);
                if (pointerIndex < 0) {
                    Log.e(LOG_TAG, "Got ACTION_MOVE event but have an invalid active pointer id.");
                    return false;
                }

                final float y = ev.getY(pointerIndex);
                //开始拖动
                startDragging(y);
                //若拖动函数判断当前需要拖动进度指示球
                if (mIsBeingDragged) {
                    //若当前需要响应拖动的是顶部指示球
                    if (mIsBeingDraggedTop) {
                        /*顶部指示球需要响应的偏移量，等于 (触摸位置 - 初始触摸位置) * 0.5，
                        表示指示球的移动距离是手指滑动距离的一半*/
                        final float overScrollTop = (y - mInitialMotionY) * DRAG_RATE;
                        //若当前触摸位置没有低于开始滑动的初始位置（若低于初始位置，表示此时顶部指示球已不再显示在屏幕上）
                        if (overScrollTop > 0) {
                            //根据手指偏移量移动顶部指示球
                            moveTopSpinner(overScrollTop);
                        } else {
                            return false;
                        }
                    }
                    //若当前需要响应拖动的是底部指示球
                    if (mIsBeingDraggedBottom) {
                        final float overScrollBottom = (y - mInitialMotionY) * DRAG_RATE;
                        if (overScrollBottom < 0) {
                            //根据手指偏移量移动底部指示球
                            moveBottomSpinner(overScrollBottom);
                        } else {
                            return false;
                        }
                    }
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                pointerIndex = ev.getActionIndex();
                if (pointerIndex < 0) {
                    Log.e(LOG_TAG,
                            "Got ACTION_POINTER_DOWN event but have an invalid action index.");
                    return false;
                }
                mActivePointerId = ev.getPointerId(pointerIndex);
                break;
            }

            case MotionEvent.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;

            case MotionEvent.ACTION_UP: {
                pointerIndex = ev.findPointerIndex(mActivePointerId);
                if (pointerIndex < 0) {
                    Log.e(LOG_TAG, "Got ACTION_UP event but don't have an active pointer id.");
                    return false;
                }

                if (mIsBeingDragged) {
                    final float y = ev.getY(pointerIndex);
                    final float overscrollTop = (y - mInitialMotionY) * DRAG_RATE;
                    mIsBeingDragged = false;
                    if (mIsBeingDraggedTop) {
                        mIsBeingDraggedTop = false;
                        finishTopSpinner(overscrollTop);
                    }
                    if (mIsBeingDraggedBottom) {
                        mIsBeingDraggedBottom = false;
                        finishBottomSpinner(Math.abs(overscrollTop));
                    }
                }
                mActivePointerId = INVALID_POINTER;
                return false;
            }
            case MotionEvent.ACTION_CANCEL:
                return false;
        }

        return true;
    }

/*    private void startDragging(float y) {
        final float yDiff = y - mInitialDownY;
        if (yDiff > mTouchSlop && !mIsBeingDragged) {
            mInitialMotionY = mInitialDownY + mTouchSlop;
            mIsBeingDragged = true;
            mTopProgress.setAlpha(STARTING_PROGRESS_ALPHA);
        }
    }*/

    /**
     * 开始拖动行为
     *
     * @param y 触摸事件的y轴坐标
     */
    private void startDragging(float y) {
        //手指移动时，移动距离的差值
        final float yDiff = y - mInitialDownY;
        //若移动距离大于最小移动阈值，且当前指示器拖动标志位还为false
        if (Math.abs(yDiff) > mTouchSlop && !mIsBeingDragged) {
            //首次移动位置，为手指落下位置+最小移动阈值
            mInitialMotionY = mInitialDownY + mTouchSlop;

            if (yDiff > 0) {
                //向下滑动，设置顶部指示球Progress的透明度，准备开始响应下拉滑动
                mTopProgress.setAlpha(STARTING_PROGRESS_ALPHA);
                if (!mTopRefreshing) {
                    mIsBeingDraggedTop = true;
                    mIsBeingDragged = true;
                }
            } else {
                if (!mBottomRefreshing) {
                    //向上滑动，设置底部指示球Progress的透明度，准备开始响应上拉滑动
                    mBottomProgress.setAlpha(STARTING_PROGRESS_ALPHA);
                    mIsBeingDraggedBottom = true;
                    mIsBeingDragged = true;
                }
            }
        }
    }

    /**
     * 开启位移动画，将顶部指示球从指定位置移动到悬垂位置
     *
     * @param from     开始位置
     * @param listener 位移动画监听
     */
    private void animateTopOffsetToCorrectPosition(int from, AnimationListener listener) {
        mTopFrom = from;
        mTopAnimateToCorrectPosition.reset();
        mTopAnimateToCorrectPosition.setDuration(ANIMATE_TO_TRIGGER_DURATION);
        mTopAnimateToCorrectPosition.setInterpolator(mDecelerateInterpolator);
        if (listener != null) {
            mTopCircleView.setAnimationListener(listener);
        }
        mTopCircleView.clearAnimation();
        mTopCircleView.startAnimation(mTopAnimateToCorrectPosition);
    }

    /**
     * 开启位移动画，将底部指示球从指定位置移动到悬垂位置
     *
     * @param from     开始位置
     * @param listener 位移动画监听
     */
    private void animateBottomOffsetToCorrectPosition(int from, AnimationListener listener) {
        mBottomFrom = from;
        mBottomAnimateToCorrectPosition.reset();
        mBottomAnimateToCorrectPosition.setDuration(ANIMATE_TO_TRIGGER_DURATION);
        mBottomAnimateToCorrectPosition.setInterpolator(mDecelerateInterpolator);
        if (listener != null) {
            mBottomCircleView.setAnimationListener(listener);
        }
        mBottomCircleView.clearAnimation();
        mBottomCircleView.startAnimation(mBottomAnimateToCorrectPosition);
    }

    private void animateTopOffsetToStartPosition(int from, AnimationListener listener) {
        if (mScale) {
            // Scale the item back down
            startTopScaleDownReturnToStartAnimation(from, listener);
        } else {
            mTopFrom = from;
            mAnimateTopToStartPosition.reset();
            mAnimateTopToStartPosition.setDuration(ANIMATE_TO_START_DURATION);
            mAnimateTopToStartPosition.setInterpolator(mDecelerateInterpolator);
            if (listener != null) {
                mTopCircleView.setAnimationListener(listener);
            }
            mTopCircleView.clearAnimation();
            mTopCircleView.startAnimation(mAnimateTopToStartPosition);
        }
    }


    /**
     * 开启底部指示球滑动回初始位置动画
     *
     * @param from     开始动画的位置
     * @param listener 动画监听
     */
    private void animateBottomOffsetToStartPosition(int from, AnimationListener listener) {
        if (mScale) {
            //当缩放标志位开启时，指示球不仅仅要回到初始位置，同时还要不停的缩小
            startBottomScaleDownReturnToStartAnimation(from, listener);
        } else {
            mBottomFrom = from;
            mAnimateBottomToStartPosition.reset();
            mAnimateBottomToStartPosition.setDuration(ANIMATE_TO_START_DURATION);
            mAnimateBottomToStartPosition.setInterpolator(mDecelerateInterpolator);
            if (listener != null) {
                mBottomCircleView.setAnimationListener(listener);
            }
            mBottomCircleView.clearAnimation();
            mBottomCircleView.startAnimation(mAnimateBottomToStartPosition);
        }
    }

    //顶部指示球若拉动超过悬垂阈值，则会触发当前动画位移到悬垂位置
    private final Animation mTopAnimateToCorrectPosition = new Animation() {
        @Override
        public void applyTransformation(float interpolatedTime, Transformation t) {
            int targetTop = 0;
            int endTarget = 0;
            if (!mUsingCustomStart) {
                endTarget = mTopSpinnerOffsetEnd - Math.abs(mOriginalOffsetTop);
            } else {
                endTarget = mTopSpinnerOffsetEnd;
            }
            targetTop = (mTopFrom + (int) ((endTarget - mTopFrom) * interpolatedTime));
            int offset = targetTop - mTopCircleView.getTop();
            setTopCircleViewTargetOffsetTopAndBottom(offset);
            mTopProgress.setArrowScale(1 - interpolatedTime);
        }
    };

    //底部指示球若拉动超过悬垂阈值，则会触发当前动画位移到悬垂位置
    private final Animation mBottomAnimateToCorrectPosition = new Animation() {
        @Override
        public void applyTransformation(float interpolatedTime, Transformation t) {
            int targetTop = 0;
            int endTarget = 0;

            if (mUsingCustomStart) {
                endTarget = mOriginalOffsetBottom - mBottomSpinnerOffsetEnd;
            } else {
                //底部指示球最终需要的移动位置的y轴坐标
                endTarget = mOriginalOffsetBottom - mBottomSpinnerOffsetEnd;
            }
            //底部指示球当前帧需要移动的目标位置
            targetTop = (mBottomFrom + (int) ((endTarget - mBottomFrom) * interpolatedTime));
            int offset = targetTop - mBottomCircleView.getTop();
            setBottomCircleViewTargetOffsetTopAndBottom(offset);
            mBottomProgress.setArrowScale(1 - interpolatedTime);
        }
    };


    /**
     * 根据传入的差值器比例值，移动顶部指示器到指定offset位置
     *
     * @param interpolatedTime 差值器时间比例 0-1
     */
    void moveToStart(float interpolatedTime) {
        int targetTop = 0;
        targetTop = (mTopFrom + (int) ((mOriginalOffsetTop - mTopFrom) * interpolatedTime));
        int offset = targetTop - mTopCircleView.getTop();
        setTopCircleViewTargetOffsetTopAndBottom(offset);
    }

    /**
     * 根据传入的差值器比例值，移动底部指示球
     *
     * @param interpolatedTime 差值器时间比例 0-1f
     */
    void moveToEnd(float interpolatedTime) {
        int targetTop = 0;
        targetTop = (mBottomFrom + (int) ((mOriginalOffsetBottom - mBottomFrom) * interpolatedTime));
        int offset = targetTop - mBottomCircleView.getTop();
        setBottomCircleViewTargetOffsetTopAndBottom(offset);
    }

    private final Animation mAnimateTopToStartPosition = new Animation() {
        @Override
        public void applyTransformation(float interpolatedTime, Transformation t) {
            moveToStart(interpolatedTime);
        }
    };

    private final Animation mAnimateBottomToStartPosition = new Animation() {
        @Override
        public void applyTransformation(float interpolatedTime, Transformation t) {
            moveToEnd(interpolatedTime);
        }
    };

    private void startTopScaleDownReturnToStartAnimation(int from,
                                                         AnimationListener listener) {
        mTopFrom = from;
        mTopStartingScale = mTopCircleView.getScaleX();
        mTopScaleDownToStartAnimation = new Animation() {
            @Override
            public void applyTransformation(float interpolatedTime, Transformation t) {
                float targetScale = (mTopStartingScale + (-mTopStartingScale * interpolatedTime));
                setTopViewScaleProgress(targetScale);
                moveToStart(interpolatedTime);
            }
        };
        mTopScaleDownToStartAnimation.setDuration(SCALE_DOWN_DURATION);
        if (listener != null) {
            mTopCircleView.setAnimationListener(listener);
        }
        mTopCircleView.clearAnimation();
        mTopCircleView.startAnimation(mTopScaleDownToStartAnimation);
    }

    /**
     * 将底部指示球弹回初始位置，同时执行缩放，让指示球越来越小
     *
     * @param from     回弹前的位置
     * @param listener 回弹动画监听
     */
    private void startBottomScaleDownReturnToStartAnimation(int from,
                                                            AnimationListener listener) {
        mBottomFrom = from;
        mBottomStartingScale = mBottomCircleView.getScaleX();
        mBottomScaleDownToStartAnimation = new Animation() {
            @Override
            public void applyTransformation(float interpolatedTime, Transformation t) {
                float targetScale = (mBottomStartingScale + (-mBottomStartingScale * interpolatedTime));
                setBottomViewScaleProgress(targetScale);
                moveToEnd(interpolatedTime);
            }
        };
        mBottomScaleDownToStartAnimation.setDuration(SCALE_DOWN_DURATION);
        if (listener != null) {
            mBottomCircleView.setAnimationListener(listener);
        }
        mBottomCircleView.clearAnimation();
        mBottomCircleView.startAnimation(mBottomScaleDownToStartAnimation);
    }

    void setTopCircleViewTargetOffsetTopAndBottom(int offset) {
        mTopCircleView.bringToFront();
        ViewCompat.offsetTopAndBottom(mTopCircleView, offset);
        //移动完顶部指示球后，会刷新顶部指示球的当前offset位置
        mCurrentTargetOffsetTop = mTopCircleView.getTop();
    }

    void setBottomCircleViewTargetOffsetTopAndBottom(int offset) {
        mBottomCircleView.bringToFront();
        ViewCompat.offsetTopAndBottom(mBottomCircleView, offset);
        //移动完底部指示球后，会刷新顶部指示球的当前offset位置
        mCurrentTargetOffsetBottom = mBottomCircleView.getTop();
    }

    private void onSecondaryPointerUp(MotionEvent ev) {
        final int pointerIndex = ev.getActionIndex();
        final int pointerId = ev.getPointerId(pointerIndex);
        if (pointerId == mActivePointerId) {
            // This was our active pointer going up. Choose a new
            // active pointer and adjust accordingly.
            final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            mActivePointerId = ev.getPointerId(newPointerIndex);
        }
    }

    /**
     * Classes that wish to be notified when the swipe gesture correctly
     * triggers a refresh should implement this interface.
     */
    public interface OnRefreshListener {
        /**
         * Called when a swipe gesture triggers a refresh.
         */
        void onRefresh();
    }

    /**
     * Classes that wish to override {@link androidx.swiperefreshlayout.widget.SwipeRefreshLayout#canChildScrollUp()} method
     * behavior should implement this interface.
     */
    public interface OnChildScrollUpCallback {
        /**
         * Callback that will be called when {@link androidx.swiperefreshlayout.widget.SwipeRefreshLayout#canChildScrollUp()} method
         * is called to allow the implementer to override its behavior.
         *
         * @param parent SwipeRefreshLayout that this callback is overriding.
         * @param child  The child view of SwipeRefreshLayout.
         * @return Whether it is possible for the child view of parent layout to scroll up.
         */
        boolean canChildScrollUp(@NonNull DuplexSwipeRefreshLayout parent, @Nullable View child);
    }
}
