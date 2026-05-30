ï»¿//var dvr_camcnt = Cookie.get("dvr_camcnt");;
var dvr_clientport;
var dvr_ocx;
var dvr_type = "sub";;
var iSetAble, iPlayBack;
var b = true;
var osd_status;
function nvrPlayerInit_IE(dvr_usr, dvr_pwd, dvr_channel) {
	dvr_ocx = document.getElementById("client_ocx");
	try {
		if (dvr_ocx.Version && versionCompare(dvr_ocx.Version, '1, 1, 0, 4') >= 0) {
			dvr_ocx.style.height = '100%'
			document.getElementsByClassName('mask_ipcam')[0].style.display = "none"

		} else {
			document.getElementsByClassName('mask_ipcam')[0].style.display = "block"
			throw 'è¯·ä¸è½½ææ°çæ¬çæ§ä»¶!';
		}
	} catch (e) {
		b = false;
		load_attract();
	}
	dvr_ocx.m_channel = dvr_channel
	dvr_ocx.SetInfoDispMode(0);
	dvr_ocx.EnableSoundAll(false);
	// $('div.mask_ipcam').css('padding-top',$('.mask_ipcam').height()/2+'px');
	document.getElementsByClassName('mask_ipcam')[0].style['padding-top'] = document.getElementsByClassName('mask_ipcam')[0].offsetHeight / 2 + 'px'
	if (b) {
		dvr_ocx = document.getElementById("client_ocx");
		if (dvr_ocx.GetChannelNum == null) {
			// alert(language_find("alert_OCX_error"));	
			return;
		}
		console.log(window.location.hostname + "," + window.location.port+","+dvr_usr+","+dvr_pwd);
		var ret = dvr_ocx.CheckConnect(window.location.hostname, parseInt(window.location.port == "" ? "10000" : window.location.port), dvr_usr, dvr_pwd);
		console.log(ret)
		// if(ret != true)
		// {
		// 	alert("alert_Connect_error");
		// }
	}
};
function nvrPlayerInit_webKit(dvr_usr, dvr_pwd) {
	dvr_type = "sub";

	var flashvars =
		{
			player_max: 16,
			usr: dvr_usr,
			pwd: dvr_pwd
		};
	var params =
		{
			player_max: 16,
			allowFullScreen: "true"
		};
	var attributes =
		{
			id: "viewer",
			name: "viewer"
		};
	swfobject.embedSWF("JaViewer.swf?player_max=4", "flashcontent", "100%", "100%", "10.2.0", "expressinstall.swf", flashvars, params, attributes);
}

window.onunload = function () {
	dvr_ocx.CloseAll();
};
window.onbeforeunload = function () {
	dvr_ocx.CloseAll();
};


function get_file_name(full_path) {
	var arr = full_path.split("/");
	return arr[arr.length - 1];
}

function ptz_send(cmd) {
	var chn = dvr_ocx.GetSelectChl();
	if (chn == -1) {
		alert("select chn");
		return;
	}

	var xmldoc = loadXMLString("<juan ver=\"0\" squ=\"abcdef\" dir=\"0\" enc=\"1\"><ptzctrl usr=\"" + dvr_usr + "\" pwd=\"" + dvr_pwd + "\" chn=\"" + chn + "\" cmd=\"" + cmd + "\" param=\"0\" /></juan>");
	var xmlstr = toXMLString(xmldoc);
	var ptzctrl_node;
	var errno_attr;
	var errno_value;


	$.ajax({
		type: "GET",
		url: "/cgi-bin/gw.cgi",
		processData: false,
		cache: false,
		data: "xml=" + xmlstr,
		async: true,
		beforeSend: function (XMLHttpRequest) {
			//alert("beforeSend");
		},
		success: function (data, textStatus) {
			//alert("recv:" + data);
			xmldoc = loadXMLString(data);
			ptzctrl_node = xmldoc.selectSingleNode("/juan/ptzctrl");
			if (ptzctrl_node != null) {
				errno_attr = ptzctrl_node.attributes.getNamedItem("errno");
				if (errno_attr != null) {
					errno_value = errno_attr.nodeValue;
					if (errno_value != "0") {
						alert("error!errno=" + errno_value);
					}
				}
			}
		},
		complete: function (XMLHttpRequest, textStatus) {
			//alert("complete:" + textStatus);
		},
		error: function (XMLHttpRequest, textStatus, errorThrown) {
			alert(language_find("alert_Communication_error_please_refresh_or_try_again_later"));
		}
	});
}

function pic_btn_down(img) {
	 dvr_ocx = document.getElementById("client_ocx");
	var file_name = get_file_name(img.src).split("_")[0];
	img.src = "images/" + file_name + "_2.jpg";
	switch (img.id) {
		case "pb_review":
			location.href = "playback.html";
			break;
		case "pb_settings":
			location.href = "settings.html";
			break;
		case "pb_1":
			if (dvr_ocx.GetCurDiv() != 0) {
				dvr_ocx.SetDispWndDivMode(0);
			}
			else {
				dvr_ocx.ChangePage(true);
			}
			break;
		case "pb_4":
			if (dvr_ocx.GetCurDiv() != 1) {
				dvr_ocx.SetDispWndDivMode(1);
			}
			else {
				dvr_ocx.ChangePage(true);
			}
			break;
		case "pb_9":
			if (dvr_ocx.GetCurDiv() != 2) {
				dvr_ocx.SetDispWndDivMode(2);
			}
			else {
				dvr_ocx.ChangePage(true);
			}
			break;
		case "pb_16":
			if (dvr_ocx.GetCurDiv() != 3) {
				dvr_ocx.SetDispWndDivMode(3);
			}
			else {
				dvr_ocx.ChangePage(true);
			}
			break;
		case "pb_16":
			if (dvr_ocx.GetCurDiv() != 3) {
				dvr_ocx.SetDispWndDivMode(3);
			}
			else {
				dvr_ocx.ChangePage(true);
			}
			break;
		case "pb_25":
			if (dvr_ocx.GetCurDiv() != 4) {
				dvr_ocx.SetDispWndDivMode(4);
			}
			else {
				dvr_ocx.ChangePage(true);
			}
			break;
		case "pb_36":
			if (dvr_ocx.GetCurDiv() != 5) {
				dvr_ocx.SetDispWndDivMode(5);
			}
			else {
				dvr_ocx.ChangePage(true);
			}
			break;
		case "pb_ptz_up":
			ptz_send(0);
			break;
		case "pb_ptz_left":
			ptz_send(2);
			break;
		case "pb_ptz_auto":
			ptz_send(8);
			break;
		case "pb_ptz_right":
			ptz_send(3);
			break;
		case "pb_ptz_down":
			ptz_send(1);
			break;
		case "pb_ptz_zd_i":
			ptz_send(9); //PTZ_CMD_IRIS_OPEN
			break;
		case "pb_ptz_zu_i":
			ptz_send(10); //PTZ_CMD_IRIS_CLOSE
			break;
		case "pb_ptz_zd_f":
			ptz_send(14); //PTZ_CMD_FOCUS_NEAR
			break;
		case "pb_ptz_zu_f":
			ptz_send(13); //PTZ_CMD_FOCUS_FAR
			break;
		case "pb_ptz_zd_z":
			ptz_send(12); //PTZ_CMD_ZOOM_IN
			break;
		case "pb_ptz_zu_z":
			ptz_send(11); //PTZ_CMD_ZOOM_OUT
			break;
		case "pb_conn_all":
			//		dvr_ocx.OpenAll();
			for (var i = 0; i < dvr_camcnt; i++) {
				dvr_ocx.OpenStream(i, dvr_type == "main" ? 0 : 1);
			}

			break;
		case "pb_disconn_all":
			for (var i = 0; i < dvr_camcnt; i++) {
				ret = dvr_ocx.GetChannelStatus(i);
				if (ret == true) {
					//dvr_ocx.OpenChannel(i);
					dvr_ocx.CloseStream(i);
				}
			}

			break;
		default:
			var ret;
			var chn = -1;
			var prefix = "chn_status_";
			if (img.id.substring(0, prefix.length) == prefix) {
				chn = parseInt(img.id.split("_")[2], 10);
				ret = dvr_ocx.GetChannelStatus(chn);
				if (ret == true) {
					dvr_ocx.CloseStream(chn);
				}
				else {
					dvr_ocx.OpenStream(chn, dvr_type == "main" ? 0 : 1);
				}
				//dvr_ocx.OpenChannel(chn);

			}
			break;
	}
}

function pic_btn_up(img) {
	var file_name = get_file_name(img.src).split("_")[0];
	img.src = "images/" + file_name + "_3.jpg";

	switch (img.id) {
		case "pb_ptz_up":
		case "pb_ptz_left":
		case "pb_ptz_right":
		case "pb_ptz_down":
		case "pb_ptz_zd_i":
		case "pb_ptz_zu_i":
		case "pb_ptz_zd_f":
		case "pb_ptz_zu_f":
		case "pb_ptz_zd_z":
		case "pb_ptz_zu_z":
			ptz_send(15); //PTZ_CMD_STOP
			break;
		default:
			break;
	}
}

var dvr_mute_all = false;
function audio_sys() {
	dvr_mute_all = !dvr_mute_all;
	dvr_ocx.EnableSoundAll(dvr_mute_all);
	if (dvr_mute_all == false) {
		$("#pb_mute_all")[0].src = "images/audio_close.jpg";
	}
	else {
		$("#pb_mute_all")[0].src = "images/audio_open.jpg";
	}
}

function switch_stream(chs,stream) {
	// dvr_type = $("#lst_type")[0].value;
	// for (var i = 0; i < dvr_camcnt; i++) {
	// 	ret = dvr_ocx.GetChannelStatus(i);
	// 	if (ret == true) {
	// 		//dvr_ocx.OpenChannel(i);
			// dvr_ocx.CloseStream(i);
			// dvr_ocx.OpenStream(i, dvr_type == "main" ? 0 : 1);
	// 	}
	// }
	dvr_ocx.CloseStream(chs);
	dvr_ocx.OpenStream(chs, stream == 0 ? 0 : 1);

	//	location.href = "view1.html?type=" + $("#lst_type")[0].value;
}

function versionCompare(ver1, ver2) {
	ver1array = ver1.replace(/\ /g, '').split(',');
	ver2array = ver2.replace(/\ /g, '').split(',');
	sv1 = parseInt(ver1array[3]) + parseInt(ver1array[2]) * 100 + parseInt(ver1array[1]) * 10000 + parseInt(ver1array[0]) * 1000000;
	sv2 = parseInt(ver2array[3]) + parseInt(ver2array[2]) * 100 + parseInt(ver2array[1]) * 10000 + parseInt(ver2array[0]) * 1000000;
	if (sv1 > sv2) {
		return 1;
	}
	else if (sv1 == sv2) {
		return 0;
	}
	else if (sv1 < sv2) {
		return -1;
	}
}

function load_attract() {
	$('div.mask_ipcam').show();
	var btn = false;
	var timer = setInterval(function () {
		if (!btn) {
			$('a.blink').css('color', '#E6AF14');
			btn = true;
		} else {
			$('a.blink').css('color', '#666');
			btn = false;
		}
	}, 500)

}
