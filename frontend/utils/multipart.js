/**
 * 手动构造 multipart/form-data 请求体
 * 适用场景: 微信小程序 wx.uploadFile 每次只能上传1个文件,
 * 需要在一次 wx.request 中提交多个文件 + 多个文本字段时使用。
 *
 * 数据流: 字符串/文件 → UTF-8 ArrayBuffer → 拼接 multipart body → wx.request(data: ArrayBuffer)
 */

/**
 * 字符串转 UTF-8 编码的 Uint8Array
 * 小程序运行环境无 TextEncoder, 手动实现 UTF-8 编码(支持中文及emoji代理对)
 */
function strToUtf8(str) {
  const bytes = []
  for (let i = 0; i < str.length; i++) {
    let code = str.charCodeAt(i)
    if (code < 0x80) {
      bytes.push(code)
    } else if (code < 0x800) {
      bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f))
    } else if (code >= 0xd800 && code <= 0xdbff) {
      // UTF-16 代理对(高位) → 转 4 字节 UTF-8
      i++
      const low = str.charCodeAt(i)
      code = 0x10000 + (((code & 0x3ff) << 10) | (low & 0x3ff))
      bytes.push(
        0xf0 | (code >> 18),
        0x80 | ((code >> 12) & 0x3f),
        0x80 | ((code >> 6) & 0x3f),
        0x80 | (code & 0x3f)
      )
    } else {
      bytes.push(
        0xe0 | (code >> 12),
        0x80 | ((code >> 6) & 0x3f),
        0x80 | (code & 0x3f)
      )
    }
  }
  return new Uint8Array(bytes)
}

/** 拼接多个 Uint8Array 为一个 */
function concat(...arrs) {
  let total = 0
  for (const a of arrs) total += a.length
  const result = new Uint8Array(total)
  let offset = 0
  for (const a of arrs) {
    result.set(a, offset)
    offset += a.length
  }
  return result
}

/**
 * 构建 multipart/form-data 请求体
 * @param {Object} textFields  文本字段 { name: value }
 * @param {Array}  fileFields  文件字段 [{ name, filename, buffer, contentType }]
 * @returns {{ buffer: ArrayBuffer, contentType: string }}
 */
function buildMultipartBody(textFields, fileFields) {
  const boundary =
    "----WebKitFormBoundary" +
    Math.random().toString(16).slice(2) +
    Date.now().toString(16)
  const CRLF = strToUtf8("\r\n")
  const parts = []

  // 文本字段
  Object.keys(textFields).forEach((name) => {
    parts.push(strToUtf8("--" + boundary + "\r\n"))
    parts.push(
      strToUtf8('Content-Disposition: form-data; name="' + name + '"\r\n\r\n')
    )
    parts.push(strToUtf8(String(textFields[name])))
    parts.push(CRLF)
  })

  // 文件字段
  fileFields.forEach((f) => {
    parts.push(strToUtf8("--" + boundary + "\r\n"))
    parts.push(
      strToUtf8(
        'Content-Disposition: form-data; name="' +
          f.name +
          '"; filename="' +
          f.filename +
          '"\r\n'
      )
    )
    parts.push(strToUtf8("Content-Type: " + f.contentType + "\r\n\r\n"))
    parts.push(new Uint8Array(f.buffer))
    parts.push(CRLF)
  })

  // 结束边界
  parts.push(strToUtf8("--" + boundary + "--\r\n"))

  const body = concat(...parts)
  return {
    buffer: body.buffer,
    contentType: "multipart/form-data; boundary=" + boundary
  }
}

module.exports = {
  buildMultipartBody,
  strToUtf8,
  concat
}
