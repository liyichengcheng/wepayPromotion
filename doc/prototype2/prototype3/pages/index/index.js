// pages/index/index.js
const { calcArticlePrice } = require('../../utils/price.js');

Page({
  data: {
    articleList: [
      {
        id: 'art_001',
        title: '倒状元：沙迪克学徒到百万年薪Java架构师',
        cover: '',
        summary: '2006年高考全省倒数第一，从沙迪克慢走丝学徒逆袭大厂Java架构师，完整成长路径与踩坑实录。',
        paidCount: 850
      },
      {
        id: 'art_002',
        title: '从月薪3k到年入百万：跨境电商小白的进阶手册',
        cover: '',
        summary: '三年时间从负债到年入百万，跨境电商的选品、运营、投放全流程经验复盘。',
        paidCount: 12450
      }
    ],
    priceConfig: {
      basePrice: 6,
      maxPrice: 20,
      threshold: 1000,
      stepUsers: 10000,
      stepAmount: 1
    }
  },

  onLoad() {
    // 解析分享参数（如果是从分享链接进入）
    const scene = wx.getLaunchOptionsSync();
    console.log('启动场景:', scene);
  },

  onShow() {
    this.refreshPrice();
  },

  refreshPrice() {
    const list = this.data.articleList.map((item) => ({
      ...item,
      currentPrice: calcArticlePrice(item.paidCount, this.data.priceConfig)
    }));
    this.setData({ articleList: list });
  },

  // 跳转文章详情
  goDetail(e) {
    const { id } = e.currentTarget.dataset;
    wx.navigateTo({
      url: `/pages/detail/detail?id=${id}`
    });
  },

  // 分享配置
  onShareAppMessage() {
    return {
      title: '逆袭故事：从高考倒数第一到百万年薪',
      path: '/pages/index/index'
    };
  }
});
