// apip2pä¸ºåä¾æ¨¡å¼ ç´æ¥æè½½å°å¨å±ä½¿ç¨å³å¯
// require(['api1'], function (apip2p) {
    // window.apip2p =  require('./modules/kp2p_js/api')
window.onload = function(){
    // æ³¨ååè° ææåè°ä»éæ³¨åä¸æ¬¡å³å¯
    apip2p.onconnect = function (api_conn, code) {
        console.log("onconnect:%d", code);
        console.log(api_conn)
        // è¿æ¥æååç»å½è®¾å¤ åç»­éè¦å°ç¨æ·ååå¯ç æ´æ¹ä¸ºç¨æ·è¾å¥
        if (code === 0){
            setTimeout(function(){
                apip2p.login(api_conn, "admin", "");
            }, 0)
        }
    }

    apip2p.onloginresult = function (api_conn, result) {
        console.log("onloginresult:%d", result)
    }

    apip2p.ondisconnect = function (api_conn, code) {
        console.log("ondisconnect:%d", code)
    }

    apip2p.onclosestream = function (api_conn, channel, stream, result) {
        console.log("onclosestream:%d, channel:%d, stream:%d", result, channel, stream);

        // æ´æ¢æ ç­¾ï¼æ¸é¤ééåå®¹
        if (api_conn.preview[channel]) {
            if (api_conn.preview[channel].player){
                var canvas_el = api_conn.preview[channel].player.node;
                var parent = canvas_el.parentNode;
                parent.removeChild(canvas_el)
                parent.appendChild(canvas_el.cloneNode(true))
            }
            api_conn.preview[channel] = null
        }
    }

    apip2p.onopenstream = function (api_conn, channel, stream, result, cam_desc) {
        console.log("onopenstream:%d, cam_desc:%s, channel:%d, stream:%d", result, cam_desc, channel, stream);
    }

    // åå§åé³é¢è§£ç å¨
    var FAudioDecode = null;
    // FAudioDecode = new JA_JSAudio(audioSendProc);
    function audioSendProc(buffer) {

    }
    // é¢è§
    apip2p.onrecvframeex = function (api_conn, frametype, data, data_len, channel, width_samplerate, height_samplewidth, enc, channels) {
        if(window.location.hash.indexOf('preview') < 0){   // ä¸å¨é¢è§çé¢æ¶ä¸è§£ç 
            return
        }
        if (frametype == 1 || frametype == 2) {

                // var laball = document.querySelector('#video-'+channel+'-div')
                // if(laball){
                //     var ani = laball.querySelector('.la-ball-scale-pulse')
                //     if (ani){
                //         ani.style.display = 'none'
                //     }
                // }
        
            // console.log("onrecvframeex------->frametype:"+frametype+",data_len:"+data_len+"--"+data[0]+"--"+data[1]+"--"+data[2]+"--"+data[3]+"--"+data[4]);
            // console.log("data_channel:",channel,"enc",enc, data)
            // æ ¹æ®H264æH265åå»ºç¸åºçplayerï¼H264ä½¿ç¨canvasï¼H265ä½¿ç¨videoï¼
            if (enc === 'H264') {
                if (api_conn.preview[channel] && !api_conn.preview[channel].player) {
                    api_conn.preview[channel].player =new JMuxer({
                        node: api_conn.preview[channel].video_id,
                        mode: "video",
                        fps:25,
                        //flushingTime: 67,
                        clearBuffer:false,
                        debug: false
                    });
                }
                // if (enc === 'AAC') return 
                // è§£ç äº¤ç»ä¸ä¸è½®äºä»¶è½®å·¡ï¼é²æ­¢é»å¡å½±åå¿è·³ååé
                setTimeout(function(){
                    api_conn.preview[channel] ? api_conn.preview[channel].player.feed({ video: data}) : ''
                }, 80)

                /* if (api_conn.preview[channel] && !api_conn.preview[channel].player) {
                    api_conn.preview[channel].player = new Player({
                        userWorker: true,
                        reuseMemory: true,
                        canvasId: api_conn.preview[channel].video_id,
                        webgl: true,
                        size: { width: 640, height: 368 }
                    })
                }
                // è§£ç äº¤ç»ä¸ä¸è½®äºä»¶è½®å·¡ï¼é²æ­¢é»å¡å½±åå¿è·³ååé
                setTimeout(function(){
                    api_conn.preview[channel] ? api_conn.preview[channel].player.decode(data) : ''
                }, 0) */
            } else if (enc === 'H265') {
                return
                if (api_conn.preview[channel] && !api_conn.preview[channel].decoder) {
                    var image = document.getElementById(api_conn.preview[channel].video_id)
                    var h265player = new libde265.RawPlayer(image)
                    var decoder = new libde265.Decoder()
                    decoder.set_image_callback(function (image) {
                        h265player._display_image(image);
                        image.free();
                    });
                    api_conn.preview[channel].decoder = decoder
                    api_conn.preview[channel].player = h265player
                    // decoder.decodePreview()   // å¯å¨è§£ç å¨
                }
                // setTimeout(function(){
                        api_conn.preview[channel] ? api_conn.preview[channel].decoder.push_data_bylive(data) : ''
                    // }, 1)
                    
                }
            }else if (frametype == 0){
                // é³é¢

            var activeCanvas = document.querySelector('canvas[class="active"]')
            if (api_conn.preview[channel] && activeCanvas && api_conn.preview[channel].video_id == activeCanvas.id) {
                if (api_conn.preview[channel] && !api_conn.preview[channel].audio) {
                    //ééé³é¢åå§å
                    var Audio_w = ""   //ljson.Live.frameHead.av.width;
                    var Audio_sampleRate = ""   //ljson.Live.frameHead.av.fps;
                    var audiochannel = 1;
                    var Audio_sampleRate = sampleRate = 8000;
                    var Audio_w = sampleWidth = 16;
                    var nAvgBytesPerSec = (Audio_sampleRate * audiochannel * Audio_w) / 8;
                    api_conn.preview[channel].audio = FAudioDecode.initPlayer(audiochannel, nAvgBytesPerSec, Audio_sampleRate);
                }
                var decbuf = G711.alawdecode(data);
                FAudioDecode.PlayBuffer(decbuf);  
            }else{
                if (api_conn.preview[channel] && api_conn.preview[channel].audio) {
                    //åæ¢ééæ¶ä¸å¨æ­æ¾å£°é³
                    if (FAudioDecode != null) {
                        FAudioDecode.stop();
                        FAudioDecode.release();
                        api_conn.preview[channel].audio = null
                    }
                }
            }
                

        }
    }

    // è¿ç¨è®¾ç½®
    apip2p.onremotesetup = function (api_conn, str, data_size, result) {
        var json = JSON.parse(str);
        console.log(json)

    }
    apip2p.onptzresult = function (a) {
        console.log("onptzresult")
        console.log(a)
    }

    // åæ¾
    apip2p.onrecvrecframe = function (api_conn, frametype, data, data_length, channel, width, height, enc, fps, time) {
        if (window.location.hash.indexOf('playback') < 0) {   // ä¸å¨åæ¾çé¢æ¶ä¸è§£ç 
            return
        }

        if (frametype == 1 || frametype == 2) {
            
            if (enc == 'H264') {    // video   video#id: playback-video
                console.log(new Date(time), data)
                if (api_conn.playback && !api_conn.playback.player){
                    api_conn.playback.player = new JMuxer({
                        node: "playback-video",
                        mode: "video",
                        // fps: 25,
                        // flushingTime: 67,
                        clearBuffer: false,
                        debug: false
                    });
                }
                setTimeout(function(){
                    api_conn.playback.player.feed({ video: data })
                }, 20)

            } else if (enc == 'H265') {   // canvas canvas#id: playback-canvas


            }


        }

    }
function doSave(value, type, name) {
    var blob;
    if (typeof window.Blob == "function") {
        blob = new Blob([value], { type: type });
    } else {
        var BlobBuilder = window.BlobBuilder || window.MozBlobBuilder || window.WebKitBlobBuilder || window.MSBlobBuilder;
        var bb = new BlobBuilder();
        bb.append(value);
        blob = bb.getBlob(type);
    }
    var URL = window.URL || window.webkitURL;
    var bloburl = URL.createObjectURL(blob);
    var anchor = document.createElement("a");
    if ('download' in anchor) {
        anchor.style.visibility = "hidden";
        anchor.href = bloburl;
        anchor.download = name;
        document.body.appendChild(anchor);
        var evt = document.createEvent("MouseEvents");
        evt.initEvent("click", true, true);
        anchor.dispatchEvent(evt);
        document.body.removeChild(anchor);
    } else if (navigator.msSaveBlob) {
        navigator.msSaveBlob(blob, name);
    } else {
        location.href = bloburl;
    }
}



    var ip = '192.168.22.210' //'192.168.199.223'//''//'192.168.12.41'//'192.168.22.210'//''//''//'192.168.199.189'////''
    var id = ''//'1080857007'//''//'924957972'//''//'1080856711'//938644594'//  ////'925932481' //

    // var ip = window.location.hostname
    //var id = 'DA4327CYKNLG478R111A';


    // è®¾å¤å¥æï¼ç½é¡µçå½å¨æä¸­åªéè¿æ¥ä¸ä¸ªè®¾å¤ï¼æä»¥å¥æä¹åªéå­å¨ä¸ä¸ªå³å¯
    window.conn = null

    function createConn() {
        conn = apip2p.create(0)
        conn.preview = {}

        // åæ¾åªéä¸ä¸ªæ ç­¾
        conn.playback = {
            player: null,
            video_id: 'playback-canvas'
        }

        if (ip) {
            console.log("connectbyip");
            apip2p.connectbyip(conn, ip, '10000')
        } else if (id) {
            apip2p.connectbyid(conn, id)
        }
    }

    // åå»ºè¿æ¥å¥æï¼éå¨ç»å½åè¿è¡
    createConn()



    // @param æ­æ¾è§é¢çæ ç­¾IDï¼video || canvasï¼, éé
    function connectVideo(video_id, channel) {

        // var item = document.querySelector('#video-'+channel+'-div');
        // var loadinghtml = document.createElement('div');
        // loadinghtml.setAttribute('class','la-ball-scale-pulse')
        // loadinghtml.innerHTML = "<div></div><div></div>";
        // item.appendChild(loadinghtml);

        console.log('video_id:%s , channel:%d', video_id, channel)

        if (conn === null) {
            createConn()
        }

        if (conn.preview[channel]) return

        conn.preview[channel] = {
            player: null,
            video_id: video_id
        }

        openStream(channel, 1)

    }

    // @param éé
    function closeVideo(channel, stream) {
        stream = stream || 1
        apip2p.close_stream(conn, channel, stream)
        // var video_div = document.querySelector('#video-'+channel+'-div')
        // var laball = video_div.querySelector('.la-ball-scale-pulse')
        // video_div.removeChild(laball)
    }

    // @param éé, ä¸»æ¬¡ç æµ(1æ¬¡ç æµï¼0ä¸»ç æµ)
    function openStream(channel, stream) {
        apip2p.open_stream(conn, channel, stream)
    }


    // æ­æ¾é¢è§
    window.addEventListener('playVideo', function (event) {
        // var dvr_type = 'sub';
        // dvr_ocx.OpenStream(event.detail.channel, dvr_type == "main" ? 0 : 1);
        // return;
        
        connectVideo(event.detail.winID, event.detail.channel)
    })

    // å³é­é¢è§
    window.addEventListener('closeVideo', function (event) {
        closeVideo(event.detail.channel)
    })

    // åæ¾
    window.addEventListener('openPlayback', function (event) {
        setTimeout(function(){
            if (!conn.playback.player) {
                // conn.playback.player = new Player({
                //     userWorker: false,
                //     reuseMemory: true,
                //     canvasId: conn.playback.video_id,
                //     webgl: true,
                //     size: { width: 640, height: 368 }
                // })

                // conn.playback.player = new JMuxer({
                //     node: "playback-video",
                //     mode: "video",
                //     fps: 25,
                //     //flushingTime: 67,
                //     clearBuffer: false,
                //     debug: true
                // });
            }
            var Timeset = new Date().getTimezoneOffset() * 60; // è·åç³»ç»æ¶åºç¸å·®å°æ¶ -8 

            var timestamp1 = event.detail.startTime;// - Timeset;
            var timestamp2 = event.detail.endTime;// - Timeset; 

            apip2p.replay_start(conn, 0, timestamp1, timestamp2, event.detail.type )//event.detail.type
            // å¯è½éè¦å°å¼å§æ¶é´åç»ææ¶é´è¿è¡æ¶åºå¤ç
            // - (new Date().getTimezoneOffset() * 60)
        }, 100)
    })

    // åæ­¢åæ¾
    window.addEventListener('closePlayback', function (event) {
        apip2p.replay_stop(conn)
    })

    // äºå°æ§å¶
    window.addEventListener('ptz_ctrl', function (event) {
        console.log(event.detail)
        var activeCanvas = document.querySelector('canvas[class="active"]')
        for (var channel in conn.preview){
            if (conn.preview[channel] && activeCanvas && conn.preview[channel].video_id == activeCanvas.id){
                apip2p.ptz_ctrl(conn, channel, event.detail.action, event.detail.speed)
                break
            }
        }
    })

    //åæ¢ç æµ
    window.addEventListener('switchStream', function (event) {
        openStream(event.detail.channel, event.detail.stream)
    })


    // é¡µé¢å·æ°ä¹åå³é­socket
    window.onbeforeunload = function () {
        conn ? apip2p.close_socket(conn) : ''
    }
// });
}
