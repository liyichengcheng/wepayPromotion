const app = getApp()
Page({
  data: {
    price: 1,
    payCount: 912,
    progressPercent: 9.12,
    isPaying: false,
    hasReadArticle: false,
    showPayModal: true,
    evidenceImages: [
      'http://www.chsl.xyz/xlrz/images/evidence/1.png',
      'http://www.chsl.xyz/xlrz/images/evidence/2.png',
      'http://www.chsl.xyz/xlrz/images/evidence/3.png',
      'http://www.chsl.xyz/xlrz/images/evidence/id.png'
    ]
  },

  onLoad(options) {
    app.banCaptureScreen()
    if (options && options.shareUid) {
      app.globalData.shareUid = options.shareUid
    }

    const price = app.globalData.currentPrice || 6
    const payCount = app.globalData.totalPayUser || 912
    const progressPercent = Math.min((payCount / 1000) * 100, 100)
    this.setData({
      price,
      payCount,
      progressPercent
    })

    const hasReadArticle = wx.getStorageSync("hasReadArticle")
    this.setData({ hasReadArticle: !!hasReadArticle })
    this.refreshPayStatus()
  },

  async refreshPayStatus() {
    try {
      await app.waitLogin(3000)
      await app.checkPayStatus()
      this.setData({ hasReadArticle: app.globalData.isPayUnlock })
    } catch (e) {
      console.error("刷新支付状态失败", e)
    }
  },

  createPayOrder() {
    if (this.data.isPaying) {
      return
    }

    if (this.data.hasReadArticle) {
      wx.navigateTo({ url: "/pages/articleReadAll/articleReadAll" })
      return
    }

    this.setData({ showPayModal: true })
  },

  closePayModal() {
    this.setData({ showPayModal: false })
  },

  async confirmPay() {
    this.setData({ showPayModal: false })
    wx.showLoading({ title: "请稍候..." })
    try {
      const userId = await app.waitLogin(3000)
      if (!userId) {
        wx.hideLoading()
        wx.showModal({
          title: '提示',
          content: '登录失败，请重试',
          showCancel: false
        })
        return
      }

      wx.hideLoading()
      const articleId = app.globalData.articleId || 10001
      const shareUid = app.globalData.shareUid || ''
      const payPrice = this.data.price
      this.setData({ isPaying: true })
      wx.showLoading({ title: "生成订单" })
      const res = await app.$request({
        url: "/pay/createOrder",
        method: "POST",
        data: {
          articleId,
          payPrice,
          parentShareUid: shareUid
        }
      })
      wx.hideLoading()
      this.wxPay(res.data)
    } catch (err) {
      wx.hideLoading()
      this.setData({ isPaying: false })
      console.error("支付流程错误", err)
      
      if (app.globalData.isDevMode) {
        wx.showLoading({ title: "重试支付中..." })
        try {
          const userId = await app.waitLogin(3000)
          wx.hideLoading()
          if (!userId) {
            wx.showModal({
              title: '提示',
              content: '登录失败，请重试',
              showCancel: false
            })
            return
          }
          const articleId = app.globalData.articleId || 10001
          const shareUid = app.globalData.shareUid || ''
          const payPrice = this.data.price
          this.setData({ isPaying: true })
          wx.showLoading({ title: "创建订单" })
          const retryRes = await app.$request({
            url: "/pay/createOrder",
            method: "POST",
            data: {
              articleId,
              payPrice,
              parentShareUid: shareUid
            }
          })
          wx.hideLoading()
          this.wxPay(retryRes.data)
        } catch (retryErr) {
          wx.hideLoading()
          this.setData({ isPaying: false })
          console.error("开发模式支付重试失败", retryErr)
          wx.showToast({ title: "支付失败，请稍后重试", icon: "none" })
        }
      } else {
         wx.showToast({ title: "支付服务暂不可用，请稍后重试", icon: "none" })
      }
    }
  },

  wxPay(payInfo) {
    wx.requestPayment({
      timeStamp: payInfo.timeStamp,
      nonceStr: payInfo.nonceStr,
      package: payInfo.packageX,
      signType: payInfo.signType,
      paySign: payInfo.paySign,
      success: () => {
        wx.setStorageSync("hasReadArticle", true)
        app.globalData.isPayUnlock = true
        this.setData({ isPaying: false })
        wx.showToast({ title: "支付成功" })
        setTimeout(() => {
          wx.redirectTo({ url: "/pages/articleReadAll/articleReadAll" })
        }, 1500)
      },
      fail: (err) => {
        this.setData({ isPaying: false })
        if (err.errMsg !== "requestPayment:fail cancel") {
          wx.showToast({ title: "支付失败", icon: "none" })
        }
      }
    })
  },

  goSharePage() {
    wx.navigateTo({ url: "/pages/shareIncome/shareIncome" })
  },

  previewImage(e) {
    const current = e.currentTarget.dataset.current
    const urls = this.data.evidenceImages
    wx.previewImage({
      current: current,
      urls: urls
    })
  },

  onShareAppMessage() {
    const myUid = app.globalData.userId
    return {
      title: "学渣逆袭：高考倒状元到百万年薪",
      path: app.globalData.shareLink || `/pages/articlePreview/articlePreview?shareUid=${myUid}`,
      imageUrl: ""
    }
  }
})
