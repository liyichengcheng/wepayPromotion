const request = require("./request")

/**
 * 解除免确认收款授权
 * 流程: 确认弹窗 -> 调后端 /income/terminateTransferAuth -> 回调通知结果
 * @param {object} pageInstance  当前页面对象(用于在成功时 setData 更新授权状态)
 * @param {function} onSuccess   可选, 解除成功后的回调
 * @param {function} onFail      可选, 解除失败后的回调
 */
function terminateTransferAuth(pageInstance, onSuccess, onFail) {
  wx.showModal({
    title: "解除免确认收款授权",
    content: "解除后，后续提现需在微信服务通知中手动确认收款。确定要解除授权吗？",
    confirmText: "解除",
    confirmColor: "#e53935",
    success: (res) => {
      if (!res.confirm) return
      doTerminateTransferAuth(pageInstance, onSuccess, onFail)
    }
  })
}

async function doTerminateTransferAuth(pageInstance, onSuccess, onFail) {
  wx.showLoading({ title: "解除中" })
  try {
    await request({
      url: "/income/terminateTransferAuth",
      method: "POST",
      silent: true
    })
    wx.hideLoading()
    // 更新页面授权状态(若调用方传入了 pageInstance 且有 transferAuthEffective 字段)
    if (pageInstance && pageInstance.data && "transferAuthEffective" in pageInstance.data) {
      pageInstance.setData({ transferAuthEffective: false })
    }
    wx.showToast({ title: "已解除授权" })
    if (typeof onSuccess === "function") onSuccess()
  } catch (err) {
    wx.hideLoading()
    wx.showToast({ title: (err && err.msg) || "解除失败, 请稍后重试", icon: "none" })
    if (typeof onFail === "function") onFail(err)
  }
}

module.exports = {
  terminateTransferAuth
}
