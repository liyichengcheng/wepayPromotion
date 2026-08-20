const request = require("./utils/request")
App({
  globalData: {
    userId: wx.getStorageSync("userId") || "",
    shareUid: "",
    articleId: 10001,
    currentPrice: 0.2,
    totalPayUser: 0,
    isLoginReady: false,
    isPayUnlock: false,
    loginCallbacks: [],
    isDevMode: false,
    shareLink: ""
  },
  $request: request,
  calcPrice(payUserCount) {
    const basePrice = 0.01
    const maxPrice = 0.05
    
    if (payUserCount <= 2) return basePrice
    const exceed = payUserCount - 2
    const addPrice = Math.ceil(exceed / 2)
    return Math.min(basePrice + addPrice * 0.01, maxPrice)
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
      wx.setStorageSync("userId", res.data.openid)
      wx.setStorageSync("token", res.data.token)
      this.globalData.userId = res.data.openid
      this.globalData.shareLink = res.data.shareLink || ""
      this.globalData.isLoginReady = true
      this.globalData.loginCallbacks.forEach(cb => cb(res.data.openid))
      this.globalData.loginCallbacks = []
      await this.checkPayStatus()
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
  },

  async checkPayStatus() {
    try {
      const res = await this.$request({
        url: "/pay/checkStatus",
        data: { articleId: this.globalData.articleId }
      })
      const paid = res.data.paid
      this.globalData.isPayUnlock = paid
      wx.setStorageSync("hasReadArticle", paid)
      if (!paid) {
        await this.getArticlePayCount()
      }
    } catch (e) {
      console.error("检查支付状态失败", e)
    }
  }
})
