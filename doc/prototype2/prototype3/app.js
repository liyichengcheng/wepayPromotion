// app.js
App({
  onLaunch() {
    // 启动时检查更新
    this.checkUpdate();
    // 初始化全局数据
    this.globalData.userInfo = null;
    this.globalData.openid = '';
    this.globalData.token = '';
    this.globalData.inviterId = '';  // 上级邀请人ID
  },

  onShow() {
    // 切前台时重新检测录屏
    if (wx.setVisualEffectOnCapture) {
      wx.setVisualEffectOnCapture({
        visualEffect: 'hidden',
        success: () => console.log('防截图能力已开启'),
        fail: (err) => console.warn('当前客户端不支持防截图', err)
      });
    }
  },

  // 检测小程序更新
  checkUpdate() {
    if (wx.getUpdateManager) {
      const updateManager = wx.getUpdateManager();
      updateManager.onCheckForUpdate((res) => {
        console.log('检查更新结果:', res.hasUpdate);
      });
      updateManager.onUpdateReady(() => {
        wx.showModal({
          title: '更新提示',
          content: '新版本已经准备好，是否重启应用？',
          success: (res) => {
            if (res.confirm) {
              updateManager.applyUpdate();
            }
          }
        });
      });
    }
  },

  // 全局数据
  globalData: {
    userInfo: null,
    openid: '',
    token: '',
    inviterId: '',
    // 文章配置（实际应从后端获取）
    articleConfig: {
      id: 'art_001',
      title: '倒状元：沙迪克学徒到百万年薪Java架构师',
      basePrice: 6,
      maxPrice: 20,
      threshold: 1000,
      stepUsers: 10000,
      stepAmount: 1,
      paidCount: 850,  // 模拟已支付人数
      commissionRate: 0.3,
      minCommission: 2
    }
  }
});
