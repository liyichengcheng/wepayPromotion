const app = getApp()
const transferAuth = require("../../utils/transferAuth")

Page({
  data: {
    userName: "微信用户",
    userId: "",
    statusBarHeight: 20,
    transferAuthEffective: false,
    transferAuthChecking: true
  },

  onLoad() {
    const sysInfo = wx.getSystemInfoSync()
    const statusBarHeight = sysInfo.statusBarHeight || 20
    this.setData({
      statusBarHeight,
      userId: app.globalData.userId || ""
    })
  },

  onShow() {
    // 每次进入页面刷新授权状态(可能从其他页面授权后回来)
    this.checkTransferAuth()
  },

  goBack() {
    wx.navigateBack({
      fail: () => {
        wx.redirectTo({ url: "/pages/articlePreview/articlePreview" })
      }
    })
  },

  goShareIncome() {
    wx.navigateTo({ url: "/pages/shareIncome/shareIncome" })
  },

  async checkTransferAuth() {
    try {
      const res = await app.$request({ url: "/income/transferAuthStatus" })
      this.setData({
        //transferAuthEffective: res.data.effective,
        transferAuthEffective: true,
        transferAuthChecking: false
      })
    } catch (e) {
      this.setData({ transferAuthChecking: false })
    }
  },

  // 解除免确认收款授权 (复用 utils/transferAuth 工具函数)
  terminateAuth() {
    transferAuth.terminateTransferAuth(this)
  }
})
