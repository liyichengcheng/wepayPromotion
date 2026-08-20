const baseUrl = "http://nj409uu6213.vicp.fun/api";
const request = (options) => {
  return new Promise((resolve, reject) => {
    wx.request({
      url: baseUrl + options.url,
      method: options.method || "GET",
      data: options.data || {},
      header: {
        "content-type": "application/json",
        "token": wx.getStorageSync("token") || ""
      },
      success: res => {
        if (res.data.code === 200) {
          resolve(res.data)
        } else {
          if (!options.silent) {
            wx.showToast({ title: res.data.msg || "请求异常", icon: "none" })
          }
          reject(res.data)
        }
      },
      fail: err => {
        if (!options.silent) {
          wx.showToast({ title: "网络请求失败", icon: "none" })
        }
        reject(err)
      }
    })
  })
}
module.exports = request