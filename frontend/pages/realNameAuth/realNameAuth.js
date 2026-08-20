const app = getApp()
const baseUrl = "http://nj409uu6213.vicp.fun/api"
const { buildMultipartBody } = require("../../utils/multipart")

// 客服微信 (与后端 IdcardConfig.customerWechat 一致)
const CUSTOMER_WECHAT = "java_cex"

// 校验正则 (与后端 RealNameValidator 规则对齐)
const RE_NAME = /^[\u4e00-\u9fa5]{2,25}$/
const RE_PHONE = /^1[3-9]\d{9}$/
const RE_IDCARD = /^\d{17}[\dXx]$/

Page({
  data: {
    statusBarHeight: 20,
    // 实名信息回显
    infoLoaded: false,
    realNameStatus: 0, // 1=已认证, 0=未认证/待审核
    submitted: false, // 是否已提交(name非空)
    savedName: "",
    savedPhone: "",
    savedIdcard: "",
    savedFrontImg: "", // base64 data url
    savedBackImg: "",
    customerWechat: CUSTOMER_WECHAT,
    // 表单 (仅未提交时使用)
    name: "",
    phoneNo: "",
    idcardNo: "",
    frontImg: "",
    backImg: "",
    submitting: false
  },

  onLoad() {
    const sysInfo = wx.getSystemInfoSync()
    this.setData({ statusBarHeight: sysInfo.statusBarHeight || 20 })
    this.loadRealNameInfo()
  },

  onShow() {
    // 从提现页跳来或提交后返回, 刷新状态
    if (this.data.infoLoaded) {
      this.loadRealNameInfo()
    }
  },

  async loadRealNameInfo() {
    try {
      const res = await app.$request({
        url: "/user/realNameInfo",
        method: "GET"
      })
      const d = res.data || {}
      const name = d.name || ""
      const status = d.status || 0
      this.setData({
        infoLoaded: true,
        realNameStatus: status,
        submitted: !!name,
        savedName: name,
        savedPhone: d.phoneNo || "",
        savedIdcard: d.idcardNo || "",
        savedFrontImg: d.frontImg
          ? "data:image/jpeg;base64," + d.frontImg
          : "",
        savedBackImg: d.backImg
          ? "data:image/jpeg;base64," + d.backImg
          : ""
      })
    } catch (e) {
      // 加载失败默认显示表单
      this.setData({ infoLoaded: true, submitted: false, realNameStatus: 0 })
    }
  },

  // 复制客服微信号
  copyWechat() {
    wx.setClipboardData({
      data: CUSTOMER_WECHAT,
      success: () => {
        wx.showToast({ title: "客服微信号已复制", icon: "none" })
      }
    })
  },

  goBack() {
    wx.navigateBack({
      fail: () => {
        wx.redirectTo({ url: "/pages/shareIncome/shareIncome" })
      }
    })
  },

  onInputName(e) {
    this.setData({ name: e.detail.value })
  },

  onInputPhone(e) {
    this.setData({ phoneNo: e.detail.value })
  },

  onInputIdcard(e) {
    this.setData({ idcardNo: e.detail.value })
  },

  chooseFront() {
    wx.chooseMedia({
      count: 1,
      mediaType: ["image"],
      sourceType: ["album", "camera"],
      success: (res) => {
        this.setData({ frontImg: res.tempFiles[0].tempFilePath })
      }
    })
  },

  chooseBack() {
    wx.chooseMedia({
      count: 1,
      mediaType: ["image"],
      sourceType: ["album", "camera"],
      success: (res) => {
        this.setData({ backImg: res.tempFiles[0].tempFilePath })
      }
    })
  },

  resolveFileInfo(tempPath, fallbackName) {
    let ext = ".jpg"
    const lower = (tempPath || "").toLowerCase()
    if (lower.endsWith(".png")) ext = ".png"
    else if (lower.endsWith(".jpeg")) ext = ".jpeg"
    else if (lower.endsWith(".jpg")) ext = ".jpg"
    const contentType = ext === ".png" ? "image/png" : "image/jpeg"
    return { filename: fallbackName + ext, contentType }
  },

  readFileBuffer(filePath) {
    return new Promise((resolve, reject) => {
      wx.getFileSystemManager().readFile({
        filePath,
        success: (res) => resolve(res.data),
        fail: (err) => reject(err)
      })
    })
  },

  submit() {
    if (this.data.submitting) return

    const { name, phoneNo, idcardNo, frontImg, backImg } = this.data

    if (!name.trim()) {
      wx.showToast({ title: "请输入真实姓名", icon: "none" })
      return
    }
    if (!phoneNo.trim()) {
      wx.showToast({ title: "请输入手机号", icon: "none" })
      return
    }
    if (!idcardNo.trim()) {
      wx.showToast({ title: "请输入身份证号", icon: "none" })
      return
    }
    if (!frontImg) {
      wx.showToast({ title: "请上传身份证人像面", icon: "none" })
      return
    }
    if (!backImg) {
      wx.showToast({ title: "请上传身份证国徽面", icon: "none" })
      return
    }

    if (!RE_NAME.test(name)) {
      wx.showToast({ title: "姓名需为2-25个汉字", icon: "none" })
      return
    }
    if (!RE_PHONE.test(phoneNo)) {
      wx.showToast({ title: "手机号格式不正确", icon: "none" })
      return
    }
    if (!RE_IDCARD.test(idcardNo)) {
      wx.showToast({ title: "身份证号格式不正确", icon: "none" })
      return
    }

    this.setData({ submitting: true })
    wx.showLoading({ title: "提交中" })

    this.doSubmit().catch((err) => {
      wx.hideLoading()
      this.setData({ submitting: false })
      wx.showToast({
        title: (err && err.msg) || "提交失败, 请重试",
        icon: "none"
      })
    })
  },

  async doSubmit() {
    const { name, phoneNo, idcardNo, frontImg, backImg } = this.data

    const [frontBuffer, backBuffer] = await Promise.all([
      this.readFileBuffer(frontImg),
      this.readFileBuffer(backImg)
    ])

    const frontInfo = this.resolveFileInfo(frontImg, "front")
    const backInfo = this.resolveFileInfo(backImg, "back")

    const { buffer, contentType } = buildMultipartBody(
      { name, phoneNo, idcardNo },
      [
        { name: "frontImg", filename: frontInfo.filename, buffer: frontBuffer, contentType: frontInfo.contentType },
        { name: "backImg", filename: backInfo.filename, buffer: backBuffer, contentType: backInfo.contentType }
      ]
    )

    const res = await new Promise((resolve, reject) => {
      wx.request({
        url: baseUrl + "/user/submitRealName",
        method: "POST",
        data: buffer,
        header: {
          "content-type": contentType,
          token: wx.getStorageSync("token") || ""
        },
        success: (resp) => {
          const data = resp.data || {}
          if (data.code === 200) {
            resolve(data)
          } else {
            reject(data)
          }
        },
        fail: () => reject({ msg: "网络请求失败" })
      })
    })

    wx.hideLoading()
    this.setData({ submitting: false })

    // 提交成功后刷新回显状态 (会进入"已提交待审核"分支)
    this.loadRealNameInfo()

    wx.showModal({
      title: "提交成功",
      content:
        (typeof res.data === "string" && res.data) ||
        "实名信息已提交, 等待审核通过后可重新申请提现",
      showCancel: false,
      confirmText: "我知道了"
    })
  }
})
