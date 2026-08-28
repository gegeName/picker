package com.chat.picker.api

import android.graphics.Color
import android.os.Parcel
import android.os.Parcelable

/**
 * Picker UI 样式配置
 */
class PickerStyle() : Parcelable {

    /** 主题色：用于选择角标背景、列表 Mask 等。默认情况下也作为确认按钮背景色。 */
    var themeColor: Int = Color.parseColor("#16A34A")

    /** 导航栏/顶部栏背景色 */
    var topBarBackgroundColor: Int = Color.parseColor("#222222")

    /** 顶部栏文字颜色（标题） */
    var topBarTextColor: Int = Color.WHITE

    /** 顶部标题按钮背景色，默认透明或轻微深色 */
    var titleButtonBackgroundColor: Int = Color.parseColor("#3A3A3A")

    /** 顶部栏按钮文字颜色（取消、切换、返回等），默认跟随 [topBarTextColor] */
    var topBarButtonTextColor: Int = Color.WHITE

    /** 列表/主要背景色 */
    var backgroundColor: Int = Color.parseColor("#FFFFFF")

    /** 底部栏背景色 */
    var bottomBarBackgroundColor: Int = Color.parseColor("#F2F2F2")

    /** 预览按钮文字颜色 */
    var previewTextColor: Int = Color.parseColor("#444444")

    /** 确认按钮背景色，默认跟随 [themeColor] */
    var confirmButtonBackgroundColor: Int = Color.parseColor("#16A34A")

    /** 确认按钮文字颜色 */
    var confirmButtonTextColor: Int = Color.WHITE

    /** 部分权限管理按钮背景色 */
    var manageButtonBackgroundColor: Int = Color.parseColor("#FFB300")

    /** 部分权限管理按钮文字颜色 */
    var manageButtonTextColor: Int = Color.WHITE

    /** 状态栏图标是否使用深色（若 [topBarBackgroundColor] 较浅，请设为 true） */
    var lightStatusBarIcons: Boolean = false

    /** 相机界面：确认按钮背景色 */
    var cameraDoneButtonBackgroundColor: Int = Color.parseColor("#FF12B76A")

    /** 相机界面：重拍按钮背景色 */
    var cameraRetakeButtonBackgroundColor: Int = Color.parseColor("#F2FFFFFF")

    /** 图片编辑/裁剪界面：底部工具按钮选中背景色 */
    var editToolSelectedBackgroundColor: Int = Color.parseColor("#16A34A")

    /** 图片编辑/裁剪界面：底部工具按钮未选中背景色 */
    var editToolUnselectedBackgroundColor: Int = Color.parseColor("#252525")

    /** 图片编辑/裁剪界面：底部工具按钮选中描边颜色 */
    var editToolSelectedStrokeColor: Int = Color.parseColor("#5BE58A")

    /** 图片编辑/裁剪界面：底部工具按钮未选中描边颜色 */
    var editToolUnselectedStrokeColor: Int = Color.parseColor("#3A3A3A")

    fun copy(): PickerStyle {
        val style = PickerStyle()
        style.themeColor = this.themeColor
        style.topBarBackgroundColor = this.topBarBackgroundColor
        style.topBarTextColor = this.topBarTextColor
        style.titleButtonBackgroundColor = this.titleButtonBackgroundColor
        style.topBarButtonTextColor = this.topBarButtonTextColor
        style.backgroundColor = this.backgroundColor
        style.bottomBarBackgroundColor = this.bottomBarBackgroundColor
        style.previewTextColor = this.previewTextColor
        style.confirmButtonBackgroundColor = this.confirmButtonBackgroundColor
        style.confirmButtonTextColor = this.confirmButtonTextColor
        style.manageButtonBackgroundColor = this.manageButtonBackgroundColor
        style.manageButtonTextColor = this.manageButtonTextColor
        style.lightStatusBarIcons = this.lightStatusBarIcons
        style.cameraDoneButtonBackgroundColor = this.cameraDoneButtonBackgroundColor
        style.cameraRetakeButtonBackgroundColor = this.cameraRetakeButtonBackgroundColor
        style.editToolSelectedBackgroundColor = this.editToolSelectedBackgroundColor
        style.editToolUnselectedBackgroundColor = this.editToolUnselectedBackgroundColor
        style.editToolSelectedStrokeColor = this.editToolSelectedStrokeColor
        style.editToolUnselectedStrokeColor = this.editToolUnselectedStrokeColor
        return style
    }

    constructor(parcel: Parcel) : this() {
        themeColor = parcel.readInt()
        topBarBackgroundColor = parcel.readInt()
        topBarTextColor = parcel.readInt()
        titleButtonBackgroundColor = parcel.readInt()
        topBarButtonTextColor = parcel.readInt()
        backgroundColor = parcel.readInt()
        bottomBarBackgroundColor = parcel.readInt()
        previewTextColor = parcel.readInt()
        confirmButtonBackgroundColor = parcel.readInt()
        confirmButtonTextColor = parcel.readInt()
        manageButtonBackgroundColor = parcel.readInt()
        manageButtonTextColor = parcel.readInt()
        lightStatusBarIcons = parcel.readByte() != 0.toByte()
        cameraDoneButtonBackgroundColor = parcel.readInt()
        cameraRetakeButtonBackgroundColor = parcel.readInt()
        editToolSelectedBackgroundColor = parcel.readInt()
        editToolUnselectedBackgroundColor = parcel.readInt()
        editToolSelectedStrokeColor = parcel.readInt()
        editToolUnselectedStrokeColor = parcel.readInt()
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(themeColor)
        parcel.writeInt(topBarBackgroundColor)
        parcel.writeInt(topBarTextColor)
        parcel.writeInt(titleButtonBackgroundColor)
        parcel.writeInt(topBarButtonTextColor)
        parcel.writeInt(backgroundColor)
        parcel.writeInt(bottomBarBackgroundColor)
        parcel.writeInt(previewTextColor)
        parcel.writeInt(confirmButtonBackgroundColor)
        parcel.writeInt(confirmButtonTextColor)
        parcel.writeInt(manageButtonBackgroundColor)
        parcel.writeInt(manageButtonTextColor)
        parcel.writeByte(if (lightStatusBarIcons) 1 else 0)
        parcel.writeInt(cameraDoneButtonBackgroundColor)
        parcel.writeInt(cameraRetakeButtonBackgroundColor)
        parcel.writeInt(editToolSelectedBackgroundColor)
        parcel.writeInt(editToolUnselectedBackgroundColor)
        parcel.writeInt(editToolSelectedStrokeColor)
        parcel.writeInt(editToolUnselectedStrokeColor)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<PickerStyle> {
        override fun createFromParcel(parcel: Parcel): PickerStyle = PickerStyle(parcel)
        override fun newArray(size: Int): Array<PickerStyle?> = arrayOfNulls(size)
    }
}
