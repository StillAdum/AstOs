'use strict';
// Shared by the real Electron/preload test and local Chromium renderer test.
module.exports = async function cases(run) {
 const wait=async expr=>{for(let i=0;i<100;i++){if(await run(expr))return;await new Promise(r=>setTimeout(r,50));}throw Error('Timed out: '+expr);};
 const check=async(expr,msg)=>{if(!await run(expr))throw Error(msg);};
 await wait("!!document.querySelector('#v-dash.on')"); // saved paired + setup complete
 await check("document.querySelector('.tab[data-view=welcome]').disabled && document.querySelector('.tab[data-view=connect]').disabled",'Connected pairing tabs not locked');
 await run("document.querySelector('.tab[data-view=welcome]').dispatchEvent(new Event('click'))");
 await check("!!document.querySelector('#v-dash.on')",'Guard failed');
 await run("document.getElementById('btnSpotify').click()");
 await wait("document.getElementById('spotifyToken').textContent==='test-token'");
 await run("document.getElementById('btnDisconnect').click()");
 await wait("!!document.querySelector('#v-connect.on')");
 await run("document.getElementById('btnExisting').click();document.getElementById('wifiHost').value='192.168.1.5';document.getElementById('wifiPairCode').value='123456';document.getElementById('btnWifi').click()");
 // Fixture now represents an unfinished tablet: Existing must still go to setup.
 await wait("!!document.querySelector('#v-setup.on #netList .netrow')");
 await check("['dash','mods','update','tools'].every(v=>document.querySelector('.tab[data-view='+v+']').disabled)",'Unfinished setup exposed other tabs');
 await run("document.querySelector('.tab[data-view=mods]').dispatchEvent(new Event('click'))");
 await check("!!document.querySelector('#v-setup.on')",'Setup-only programmatic guard');
 await check("!document.querySelector('#netList img')",'SSID injected HTML');
 await run("document.querySelector('#netList .netrow').click()");
 await check("document.getElementById('wifiSsid').value==='<img src=x onerror=alert(1)>'",'SSID selection');
 await run("document.getElementById('btnRescanWifi').click()");
 await wait("document.getElementById('setupStatus').textContent.includes('scan unavailable')");
 await check("document.getElementById('city').value==='Test city' && document.querySelector('#themeGrid .sel').dataset.key==='pine'",'Scan error lost saved settings/theme');
 console.log('RENDERER PASS: saved resume, authoritative setup state, pairing guards, disconnect, SSID safety, settings survive scan failure, Spotify token');
};
