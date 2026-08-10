// pages/detail/detail.js
const { calcArticlePrice, calcCommission, formatPrice } = require('../../utils/price.js');
const { enableAntiScreenshot, watchScreenRecord } = require('../../utils/security.js');
const api = require('../../utils/api.js');
const app = getApp();

Page({
  data: {
    articleId: '',
    title: '倒状元：沙迪克学徒到百万年薪Java架构师',
    previewContent: '2006年高考全省倒数第一，家境贫寒，进厂做沙迪克慢走丝线切割学徒，车间油污、高强度劳作、旁人的冷眼屈辱，无数个夜晚站在江边想要一跃而下……',
    fullContent: '',
    hasPaid: false,           // 是否已支付
    showPayMask: true,        // 是否显示付费遮罩
    showSuccessModal: false,  // 是否显示支付成功弹窗
    currentPrice: 6,          // 当前售价
    commissionAmount: 2,      // 返佣金额
    inviterId: '',            // 邀请人ID（上级）
    inviteCode: '',           // 分享者的邀请码
    orderNo: '',              // 订单号
    priceConfig: {},
    paidCount: 0
  },

  onLoad(options) {
    const articleId = options.id || 'art_001';
    const inviterId = options.inviter || '';  // 分享链接中的邀请人

    // 获取文章配置
    const config = app.globalData.articleConfig;
    const paidCount = config.paidCount;
    const currentPrice = calcArticlePrice(paidCount, config);
    const commissionAmount = calcCommission(currentPrice, config);

    this.setData({
      articleId,
      inviterId,
      priceConfig: config,
      paidCount,
      currentPrice: formatPrice(currentPrice),
      commissionAmount: formatPrice(commissionAmount)
    });

    // 开启防截图
    enableAntiScreenshot();
    // 监听录屏
    watchScreenRecord((state) => {
      if (state === 'start') {
        wx.showToast({ title: '检测到录屏，请停止录屏以保护内容', icon: 'none' });
      }
    });

    // 检查登录状态
    this.checkLogin();
    // 加载文章完整内容（仅付费用户可请求）
    this.loadArticleContent();

    // 如果是通过分享进入，记录上下级关系
    if (inviterId) {
      this.bindRelation(inviterId);
    }
  },

  onShow() {
    // 页面显示时再次开启防截图（切回前台时）
    enableAntiScreenshot();
  },

  onHide() {
    // 页面隐藏时不做特殊处理
  },

  onUnload() {},

  // 检查登录
  checkLogin() {
    if (!app.globalData.openid) {
      this.wxLogin();
    }
  },

  // 微信登录
  wxLogin() {
    wx.login({
      success: (res) => {
        if (res.code) {
          // 实际项目：将 code 发到后端换取 openid 和 token
          // 此处模拟
          app.globalData.openid = 'mock_openid_' + res.code;
          app.globalData.token = 'mock_token';
          console.log('[登录] 获取到 openid:', app.globalData.openid);
        }
      }
    });
  },

  // 加载文章内容
  loadArticleContent() {
    // 实际项目：调用后端接口，根据用户支付状态返回预览内容或完整内容
    // 模拟：仅当已支付才返回完整内容
    const fullContent = `
      <p>靠着死磕韧劲自学CAD、Java，从外包底层开发一步步深耕JVM、Disruptor、JRaft分布式架构，33岁拿到大厂百万年薪offer。这条路布满血泪，完整的成长经历、踩坑记录、技术学习路线全部记录在正文全文中。</p>
      <p>【2006-2010 工厂岁月】高考落榜后我进了一家做精密模具的台资厂，做沙迪克慢走丝线切割学徒。月薪 800，每天工作 12 小时。手上被切割液泡得发白皲裂，冬天冻得握不住机床把手。</p>
      <p>【自学编程起点】2008 年春节回家，二叔带回一台二手笔记本电脑。我从网吧下载 JDK1.6 和谭浩强的《C 语言程序设计》，从此走上了不归路。</p>
      <p>【外包公司的炼狱】2010 年进入一家日资外包公司做 Java 开发，月薪 3500。期间自学了 Spring、MyBatis、Redis、Dubbo，加班到凌晨是常态。</p>
      <p>【跳槽大厂的关键一跃】2015 年开始系统学习 JVM 底层、Netty、Disruptor 源码、JRaft 共识算法。2018 年面试 30 余家，最终拿到某大厂 P7+ offer，年薪 100 万+。</p>
      <p>【完整技术学习路线】从 Java 基础到 JVM 调优、从 Spring 源码到分布式架构，10 年技术学习路径全公开，附完整书单、源码阅读顺序、练手项目推荐。</p>
    `;
    this.setData({ fullContent });
  },

  // 绑定上下级关系
  bindRelation(inviterId) {
    if (!app.globalData.openid) return;
    // 实际项目：调用后端接口绑定
    console.log('[关系绑定] 当前用户:', app.globalData.openid, '邀请人:', inviterId);
    // 后端校验：当前用户是否已有上级，没有则绑定
  },

  // 立即支付
  onPay() {
    if (!app.globalData.openid) {
      this.wxLogin();
      wx.showToast({ title: '请先登录', icon: 'none' });
      return;
    }

    wx.showLoading({ title: '正在下单...' });

    // 实际项目：调用后端创建订单接口
    // 模拟订单创建
    setTimeout(() => {
      const orderNo = 'ORDER' + Date.now();
      const price = this.data.currentPrice;

      // 调用微信支付
      this.invokeWxPay(orderNo, price);
    }, 800);
  },

  // 调用微信支付
  invokeWxPay(orderNo, price) {
    wx.hideLoading();

    // 实际项目：先调后端 /order/create 获取支付参数
    // 模拟支付参数
    const payParams = {
      timeStamp: String(Math.floor(Date.now() / 1000)),
      nonceStr: Math.random().toString(36).slice(2),
      package: `prepay_id=mock_prepay_${orderNo}`,
      signType: 'RSA',
      paySign: 'mock_sign_' + Math.random().toString(36).slice(2)
    };

    wx.requestPayment({
      ...payParams,
      success: () => {
        console.log('[支付] 成功');
        this.paySuccess(orderNo);
      },
      fail: (err) => {
        console.error('[支付] 失败', err);
        if (err.errMsg.includes('cancel')) {
          wx.showToast({ title: '已取消支付', icon: 'none' });
        } else {
          wx.showToast({ title: '支付失败，请重试', icon: 'none' });
        }
      }
    });
  },

  // 支付成功处理
  paySuccess(orderNo) {
    this.setData({
      hasPaid: true,
      showPayMask: false,
      showSuccessModal: true,
      orderNo
    });

    // 实际项目：通知后端校验支付结果、发放返佣
  },

  // 关闭支付成功弹窗
  closeSuccessModal() {
    this.setData({ showSuccessModal: false });
  },

  // 分享文章
  onShare() {
    // 跳转到分享中心
    wx.navigateTo({
      url: `/pages/share/share?articleId=${this.data.articleId}&price=${this.data.currentPrice}`
    });
  },

  // 分享给好友
  onShareAppMessage() {
    const inviteCode = 'U' + (app.globalData.openid || 'GUEST').slice(-6).toUpperCase();
    return {
      title: `我已解锁《${this.data.title}》，分享给你一起看`,
      path: `/pages/detail/detail?id=${this.data.articleId}&inviter=${inviteCode}`,
      imageUrl: ''  // 分享图
    };
  },

  // 分享到朋友圈
  onShareTimeline() {
    return {
      title: this.data.title
    };
  },

  // 阻止长按选择
  noop() {}
});
