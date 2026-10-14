// ==UserScript==
// @name         示例·链接高亮
// @namespace    octopus
// @version      1.0.0
// @description  browser-script 示例:在 example.com 给所有链接加黄色高亮,演示用户脚本注入与GM_addStyle。
// @author       ECHO
// @match        *://example.com/*
// @match        *://*.example.com/*
// @run-at       document-end
// @grant        GM_addStyle
// ==/UserScript==

(function() {
    'use strict';
    try {
        GM_addStyle('a { background-color: #fff3a0 !important; border-radius: 3px !important; }');
        document.querySelectorAll('a').forEach(function(a) {
            a.style.backgroundColor = '#fff3a0';
            a.style.borderRadius = '3px';
        });
        console.log('[octopus demo-highlight] applied to ' + document.querySelectorAll('a').length + ' links');
    } catch (e) {
        console.warn('[octopus demo-highlight] error:', e);
    }
})();
