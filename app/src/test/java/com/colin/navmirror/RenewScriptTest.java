package com.colin.navmirror;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 续期脚本的回归保护：2026-09-22 实测 core.autocrlf=true 把工作区 renew_cert.sh
 * 转成 CRLF，WSL bash 直接语法报错；且管道里 certbot 失败会被 tail 的成功掩盖。
 * 这里固定住：LF 行结尾、set -euo pipefail、凭据清理、输出目录由调用方显式传入。
 */
public class RenewScriptTest {

    private static String script() throws IOException {
        File file = new File("../renew_cert.sh");            // Gradle 单测 cwd = app 模块目录
        if (!file.isFile()) file = new File("renew_cert.sh");
        Assume.assumeTrue("renew_cert.sh not found (unexpected working directory)", file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void renewScript_isLfOnly() throws IOException {
        String source = script();
        assertFalse("renew_cert.sh 含 CR（autocrlf 又把它转成 CRLF，WSL bash 会语法报错）——"
                + "用 .gitattributes *.sh text eol=lf + git add --renormalize 修复",
                source.contains("\r"));
    }

    @Test
    public void renewScript_failsOnCertbotFailureAndCleansCredentials() throws IOException {
        String source = script();
        // 只有 set -e 时 certbot 失败会被 tail 的成功掩盖，脚本继续复制旧证书并报成功
        assertTrue(source.contains("set -euo pipefail"));
        // 凭据临时文件无论成败都要清理
        assertTrue(source.contains("trap 'rm -f \"$CREDS\"' EXIT"));
    }

    @Test
    public void renewScript_outputDirComesFromCallerNotUserNameGuess() throws IOException {
        String source = script();
        // WSL 用户名 != Windows 用户名：默认值必须与 Windows 目录无关
        assertTrue(source.contains("CERT_OUT:-/tmp/certs"));
        assertFalse("不得用 $USER 猜 Windows 用户目录", source.contains("/mnt/c/Users/$USER"));
        // token 变量名：DESEC_TOKEN 为主，DESC_TOKEN 兼容（历史名易被当成拼写错误）
        assertTrue(source.contains("${DESEC_TOKEN:-}"));
        assertTrue(source.contains("${DESC_TOKEN:-}"));
    }

    @Test
    public void gitAttributes_pinLineEndings() throws IOException {
        File file = new File("../.gitattributes");
        if (!file.isFile()) file = new File(".gitattributes");
        Assume.assumeTrue(".gitattributes not found", file.isFile());
        String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue(".gitattributes 必须固定 *.sh eol=lf", source.contains("*.sh") && source.contains("eol=lf"));
        assertTrue(".gitattributes 必须固定 *.bat eol=crlf", source.contains("*.bat") && source.contains("eol=crlf"));
    }
}
