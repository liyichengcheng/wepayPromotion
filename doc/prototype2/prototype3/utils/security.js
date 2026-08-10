// utils/security.js - 防截图、防录屏、防复制等安全策略

/**
 * 开启防截图（仅支持客户端 8.0.16+）
 * Android 端可实现系统级禁止截屏
 * iOS 端仅能监听截屏事件做提示
 */
function enableAntiScreenshot() {
  // 系统级防截屏（仅 Android 客户端）
  if (wx.setVisualEffectOnCapture) {
    wx.setVisualEffectOnCapture({
      visualEffect: 'hidden',
      success: () => {
        console.log('[安全] 防截图已开启');
      },
      fail: (err) => {
        console.warn('[安全] 当前客户端不支持防截图', err);
      }
    });
  }

  // 监听用户截屏事件
  wx.onUserCaptureScreen(() => {
    wx.showModal({
      title: '温馨提示',
      content: '本文章为付费内容，截屏分享将影响您的账号安全，请勿截屏传播。',
      showCancel: false,
      confirmText: '我知道了'
    });
  });
}

/**
 * 禁用长按复制、选择文字
 * 在 wxml 中用 user-select: text/none 控制
 * 也可以通过 disable-scroll 等属性控制
 */
const noCopyStyle = `
  -webkit-user-select: none;
  user-select: none;
  -webkit-touch-callout: none;
`;

/**
 * 监听录屏事件（仅支持客户端 7.0.7+）
 */
function watchScreenRecord(callback) {
  if (wx.onScreenRecordingStateChanged) {
    wx.onScreenRecordingStateChanged((res) => {
      console.log('[安全] 录屏状态变化:', res.state);
      if (res.state === 'start' && typeof callback === 'function') {
        callback('start');
      } else if (res.state === 'stop' && typeof callback === 'function') {
        callback('stop');
      }
    });
  }
}

module.exports = {
  enableAntiScreenshot,
  noCopyStyle,
  watchScreenRecord
};
