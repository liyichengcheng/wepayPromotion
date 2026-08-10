package com.wepay.promotion.mapper;

import com.wepay.promotion.entity.Article;
import org.apache.ibatis.annotations.Param;

public interface ArticleMapper {
    Article selectByArticleId(@Param("articleId") Long articleId);
}
