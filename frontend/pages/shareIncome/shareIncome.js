const app = getApp()
Page({
  data: {
    totalMoney: "0.00",
    todayIncome: "0.00",
    withdrawMoney: "0.00",
    todayCount: 0,
    price: 6,
    comm: "2.00",
    myUid: "",
    statusBarHeight: 20
  },

  onLoad() {
    const sysInfo = wx.getSystemInfoSync()
    const statusBarHeight = sysInfo.statusBarHeight || 20
    this.setData({ statusBarHeight })
    const uid = app.globalData.userId
    const price = app.globalData.currentPrice || 6
    const comm = app.calcCommission(price) || 2
    this.setData({
      myUid: uid,
      price,
      comm: comm.toFixed(2),
      todayIncome: comm.toFixed(2)
    })
    this.getUserIncome()
  },

  goBack() {
    wx.navigateBack({
      fail: () => {
        wx.redirectTo({ url: "/pages/articlePreview/articlePreview" })
      }
    })
  },

  async getUserIncome() {
    const { userId } = app.globalData
    try {
      const res = await app.$request({
        url: "/income/getUserIncome",
        data: { userId }
      })
      this.setData({
        totalMoney: res.data.totalIncome || "0.00",
        todayIncome: res.data.todayIncome || this.data.todayIncome,
        withdrawMoney: res.data.withdrawable || "0.00",
        todayCount: res.data.todayCount || 0
      })
    } catch (e) {
      console.error("获取佣金失败", e)
    }
  },

  copyLink() {
    const { myUid } = this.data
    const link = `pages/articlePreview/articlePreview?shareUid=${myUid}`
    wx.setClipboardData({
      data: link,
      success: () => {
        wx.showToast({ title: "链接已复制，快去分享吧" })
      }
    })
  },

  onShareAppMessage() {
    const myUid = app.globalData.userId
    return {
      title: "学渣逆袭：高考倒状元到百万年薪，快来看看吧！",
      path: `/pages/articlePreview/articlePreview?shareUid=${myUid}`,
      imageUrl: ""
    }
  },

  async applyWithdraw() {
    const { userId } = app.globalData
    wx.showLoading({ title: "提交提现申请" })
    try {
      await app.$request({
        url: "/income/applyWithdraw",
        method: "POST",
        data: { userId }
      })
      wx.hideLoading()
      wx.showToast({ title: "提现申请提交成功" })
    } catch (err) {
      wx.hideLoading()
    }
  }
})
