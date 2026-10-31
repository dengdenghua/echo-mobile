/* Optional browser presentation checks; network/media are covered by the host integration lane. */
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const path=require('node:path');
const fs=require('node:fs');
const modulePath=process.env.ECHO_REMOTE_PLAYWRIGHT_MODULE;
test('connection route stays readable at phone widths and both permission levels', {skip:!modulePath}, async()=>{
  const {chromium}=require(modulePath);
  const browser=await chromium.launch({channel:process.env.ECHO_REMOTE_BROWSER_CHANNEL||undefined,headless:true});
  const results=[];
  try {
    const page=await browser.newPage();
    await page.addInitScript(()=>{
      Object.defineProperty(window,'RemoteDesktopSession',{configurable:true,set(Native){
        Object.defineProperty(window,'RemoteDesktopSession',{value:class extends Native {
          constructor(options){super(options);window.presentationState=options.onState;window.presentationStats=options.onStats;}
        }});
      }});
      window.Android={signal:()=>true,ready:()=>{},supportsMiniPlayer:()=>false,showKeyboard:()=>{},hideKeyboard:()=>{}};
    });
    for(const width of [360,390,420]) {
      await page.setViewportSize({width,height:850});
      await page.goto(pathToFileURL(path.resolve(__dirname,'../app/src/main/assets/pc_remote.html')).href);
      for(const permission of ['view','control']) {
        for(const [route,label] of Object.entries({direct:'直连',turn:'TURN 中继',wss:'WSS 兼容连接',unknown:'路径检测中'})) {
          await page.evaluate(({permission,route})=>window.presentationState('connected',{permission,path:route}),{permission,route});
          await page.locator('#btn-actions').click();
          assert.equal(await page.locator('#connection-path').textContent(),'当前连接：'+label);
          const fits=await page.locator('#connection-path').evaluate(el=>el.scrollWidth<=el.clientWidth && el.getBoundingClientRect().width>0);
          assert.ok(fits,`${width}/${permission}/${route}: full label clipped`);
          assert.ok((await page.locator('#st').textContent()).startsWith(label));
          assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
          if(width===390&&permission==='control'&&process.env.ECHO_ROUTING_LAYOUT_EVIDENCE) {
            fs.mkdirSync(process.env.ECHO_ROUTING_LAYOUT_EVIDENCE,{recursive:true});
            await page.screenshot({path:path.join(process.env.ECHO_ROUTING_LAYOUT_EVIDENCE,route+'.png')});
          }
          results.push({width,permission,route,full_label_visible:true});
          await page.locator('[data-close="actions"]').click();
        }
      }
    }
    await page.evaluate(()=>window.presentationState('offline',{}));
    assert.equal(await page.locator('#connection-path').textContent(),'当前连接：未连接');
    if(process.env.ECHO_ROUTING_LAYOUT_EVIDENCE) fs.writeFileSync(path.join(process.env.ECHO_ROUTING_LAYOUT_EVIDENCE,'results.json'),JSON.stringify({passed:true,cases:results.length,presentation_only:true,results},null,2));
    console.log(`${results.length} narrow-layout scenarios passed; disconnected path cleared.`);
  } finally {await browser.close();}
});
