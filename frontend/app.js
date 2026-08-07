const request = require("./utils/request")
App({
  globalData: {
    userId: wx.getStorageSync("userId") || "",
    shareUid: "",
    articleId: 10001,
    isPayUnlock: false,
    currentPrice: 6,
    totalPayUser: 0,
    isLoginReady: false,
    loginCallbacks: [],
    isDevMode: false
  },
  $request: request,

  calcPrice(payUserCount) {
    const basePrice = 6
    const maxPrice = 20
    if (payUserCount <= 1000) return basePrice
    const exceed = payUserCount - 1000
    const addPrice = Math.floor(exceed / 10000)
    return Math.min(basePrice + addPrice, maxPrice)
  },

  calcCommission(price) {
    const comm = Number((price * 0.3).toFixed(2))
    return comm >= 2 ? comm : 2
  },

  banCaptureScreen() {
    if (wx.setVisualEffectOnCapture) {
      wx.setVisualEffectOnCapture({ visualEffect: "hidden" })
    }
  },

  waitLogin(timeout = 5000) {
    return new Promise((resolve) => {
      if (this.globalData.isLoginReady) {
        resolve(this.globalData.userId)
        return
      }
      const timer = setTimeout(() => {
        const userId = this.globalData.userId || wx.getStorageSync("userId") || "local_user_" + Date.now()
        if (!this.globalData.userId) {
          this.globalData.userId = userId
          wx.setStorageSync("userId", userId)
        }
        resolve(userId)
      }, timeout)
      this.globalData.loginCallbacks.push((uid) => {
        clearTimeout(timer)
        resolve(uid)
      })
    })
  },

  async login() {
    try {
      const loginRes = await new Promise((res, rej) => {
        wx.login({
          success: res,
          fail: rej
        })
      })
      const { code } = loginRes
      const res = await this.$request({
        url: "/user/wxLogin",
        method: "POST",
        data: { code }
      })
      wx.setStorageSync("userId", res.data.userId)
      wx.setStorageSync("token", res.data.token)
      this.globalData.userId = res.data.userId
      this.globalData.isLoginReady = true
      this.globalData.loginCallbacks.forEach(cb => cb(res.data.userId))
      this.globalData.loginCallbacks = []
    } catch (err) {
      console.log("登录失败，使用本地用户", err)
      const localUserId = wx.getStorageSync("userId") || "local_user_" + Date.now()
      this.globalData.userId = localUserId
      wx.setStorageSync("userId", localUserId)
      this.globalData.isLoginReady = true
      this.globalData.loginCallbacks.forEach(cb => cb(localUserId))
      this.globalData.loginCallbacks = []
    }
  },

  onLaunch(options) {
    if (options.query && options.query.shareUid) {
      this.globalData.shareUid = options.query.shareUid
    }
    this.login()
    this.banCaptureScreen()
    this.getArticlePayCount()
  },

  async getArticlePayCount() {
    try {
      const res = await this.$request({
        url: "/article/getPayTotal",
        data: { articleId: this.globalData.articleId }
      })
      this.globalData.totalPayUser = res.data.totalPayUser
      const price = this.calcPrice(res.data.totalPayUser)
      this.globalData.currentPrice = price
    } catch (e) {
      console.error("获取付费人数失败，使用默认值", e)
    }
  }
})
