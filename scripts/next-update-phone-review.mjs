/* global document */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
const require=createRequire(import.meta.url);
const root=fileURLToPath(new URL('../',import.meta.url));
const {chromium}=require('C:/Users/despa/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
/** Isolated browser + in-process local HTTP fixture, no network or production account. */
export async function reviewPhone(fixture){
 const executable='C:/Users/despa/.cache/puppeteer/chrome';
 const version=fs.readdirSync(executable).find(n=>n.startsWith('win'));
 const browser=await chromium.launch({executablePath:path.join(executable,version,'chrome-win64/chrome.exe'),headless:true,args:['--disable-gpu'],env:{...process.env,TEMP:path.join(root,'.tools/work-temp'),TMP:path.join(root,'.tools/work-temp')}});
 try{
  const context=await browser.newContext({viewport:{width:375,height:812},deviceScaleFactor:1});
  await context.addCookies(fixture.cookie.split('; ').map(pair=>{const i=pair.indexOf('=');return {name:pair.slice(0,i),value:pair.slice(i+1),url:'https://localhost',secure:true,httpOnly:true,sameSite:'Strict'};}));
  const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await context.route('**/*',async route=>{
   const request=route.request(),url=new URL(request.url());assert.equal(url.origin,'https://localhost','No external browser requests allowed');
   if(url.pathname.startsWith('/api/')){
    const response=await fixture.inject({method:request.method(),url:url.pathname+url.search,headers:await request.allHeaders(),...(request.postData()?{payload:request.postData()}:{} )});
    await route.fulfill({status:response.statusCode,contentType:'application/json',body:response.body});return;
   }
   const dist=path.join(root,'apps/admin/dist');
   const assetPath=url.pathname.replace(/^\/admin(?=\/assets\/)/,'');
   const target=assetPath.startsWith('/assets/')?path.resolve(dist,'.'+assetPath):path.join(dist,'index.html');
   assert.ok(target.startsWith(dist+path.sep));
   await route.fulfill({status:200,contentType:target.endsWith('.js')?'text/javascript':target.endsWith('.css')?'text/css':'text/html',body:fs.readFileSync(target)});
  });
  await page.goto('https://localhost/admin/phone');
  await page.getByRole('heading',{name:'Подозрения',exact:true}).waitFor();
  await page.getByText('NextPlayer',{exact:true}).first().waitFor();
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=375),'No horizontal overflow at 375px');
  await page.screenshot({path:path.join(fixture.out,'phone-suspicions-375.png'),fullPage:true});
  await page.getByRole('button',{name:'Игроки',exact:true}).click();
  await page.getByRole('heading',{name:'Игроки',exact:true}).waitFor();
  await page.getByLabel('Ник, логин или почта').fill('NextPlayer');
  await page.getByRole('button',{name:'Найти',exact:true}).click();
  await page.getByRole('heading',{name:'NextPlayer',exact:true}).waitFor();
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=375));
  await page.screenshot({path:path.join(fixture.out,'phone-players-375.png'),fullPage:true});
  assert.deepEqual(errors,[],'No browser runtime errors');
 }finally{await browser.close();}
}
