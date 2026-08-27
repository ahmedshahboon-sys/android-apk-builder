extends Control

const APP_NAME := "مربوعة"
const API_URL := "__SUPABASE_URL__"
const API_KEY := "__SUPABASE_PUBLISHABLE_KEY__"
const SESSION_FILE := "user://marboua_session.cfg"

var session_token := ""
var my_user_id := 0
var my_username := ""
var active_user_id := 0
var active_username := ""
var page := "auth"
var status_label: Label
var body: VBoxContainer
var poll_timer: Timer

func _ready() -> void:
	setup_theme()
	poll_timer = Timer.new()
	poll_timer.wait_time = 3.0
	poll_timer.timeout.connect(_on_poll)
	add_child(poll_timer)
	if load_session():
		show_users()
	else:
		show_auth()

func setup_theme() -> void:
	var t := Theme.new()
	if ResourceLoader.exists("res://assets/Cairo-Regular.ttf"):
		t.default_font = load("res://assets/Cairo-Regular.ttf")
	t.default_font_size = 28
	t.set_color("font_color", "Label", Color("f7f8ff"))
	t.set_color("font_color", "Button", Color("ffffff"))
	t.set_color("font_color", "LineEdit", Color("ffffff"))
	t.set_color("font_placeholder_color", "LineEdit", Color("96a0ba"))
	theme = t

func clear_screen() -> void:
	for c in get_children():
		if c != poll_timer:
			c.queue_free()

func make_background() -> VBoxContainer:
	clear_screen()
	var bg := ColorRect.new()
	bg.color = Color("07122b")
	bg.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	add_child(bg)
	var margin := MarginContainer.new()
	margin.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	margin.add_theme_constant_override("margin_left", 36)
	margin.add_theme_constant_override("margin_right", 36)
	margin.add_theme_constant_override("margin_top", 44)
	margin.add_theme_constant_override("margin_bottom", 36)
	add_child(margin)
	body = VBoxContainer.new()
	body.add_theme_constant_override("separation", 18)
	margin.add_child(body)
	return body

func title(text: String, size: int = 48) -> Label:
	var l := Label.new()
	l.text = text
	l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	l.add_theme_font_size_override("font_size", size)
	return l

func line(placeholder: String, secret: bool = false) -> LineEdit:
	var e := LineEdit.new()
	e.placeholder_text = placeholder
	e.secret = secret
	e.custom_minimum_size = Vector2(0, 72)
	e.add_theme_font_size_override("font_size", 27)
	e.text_direction = Control.TEXT_DIRECTION_RTL
	return e

func button(text: String) -> Button:
	var b := Button.new()
	b.text = text
	b.custom_minimum_size = Vector2(0, 72)
	b.add_theme_font_size_override("font_size", 27)
	return b

func show_auth() -> void:
	page = "auth"
	poll_timer.stop()
	var root := make_background()
	var logo := TextureRect.new()
	if ResourceLoader.exists("res://icon.jpg"):
		logo.texture = load("res://icon.jpg")
	logo.custom_minimum_size = Vector2(0, 260)
	logo.expand_mode = TextureRect.EXPAND_FIT_WIDTH_PROPORTIONAL
	logo.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	root.add_child(logo)
	root.add_child(title(APP_NAME))
	var sub := title("تواصل بكل بساطة", 24)
	sub.modulate = Color("28d6dd")
	root.add_child(sub)
	var user := line("اسم المستخدم")
	var password_input := line("كلمة المرور", true)
	root.add_child(user)
	root.add_child(password_input)
	status_label = Label.new()
	status_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	status_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	status_label.modulate = Color("ffcf70")
	root.add_child(status_label)
	var login := button("دخول")
	login.pressed.connect(func(): await do_login(user.text, password_input.text))
	root.add_child(login)
	var reg := button("إنشاء حساب جديد")
	reg.pressed.connect(func(): await do_register(user.text, password_input.text))
	root.add_child(reg)
	password_input.text_submitted.connect(func(_t): await do_login(user.text, password_input.text))

func backend_ready() -> bool:
	if API_URL.begins_with("__") or API_KEY.begins_with("__"):
		status_label.text = "تعذر تحميل إعدادات الاتصال."
		return false
	return true

func do_register(username: String, password: String) -> void:
	if username.strip_edges().length() < 3 or password.length() < 4:
		status_label.text = "اسم المستخدم 3 أحرف على الأقل، وكلمة المرور 4 أحرف على الأقل."
		return
	if not backend_ready(): return
	status_label.text = "جاري إنشاء الحساب..."
	var r = await api_rpc("marboua_register", {"p_username": username.strip_edges(), "p_password": password})
	if r.ok:
		status_label.text = "تم إنشاء الحساب. جاري الدخول..."
		await do_login(username, password)
	else:
		status_label.text = r.error

func do_login(username: String, password: String) -> void:
	if not backend_ready(): return
	status_label.text = "جاري الدخول..."
	var r = await api_rpc("marboua_login", {"p_username": username.strip_edges(), "p_password": password})
	if not r.ok:
		status_label.text = r.error
		return
	var data = r.data
	if data is Array and data.size() > 0:
		data = data[0]
	if typeof(data) != TYPE_DICTIONARY:
		status_label.text = "تعذر قراءة بيانات الدخول."
		return
	session_token = str(data.get("session_token", ""))
	my_user_id = int(data.get("user_id", 0))
	my_username = str(data.get("username", username))
	if session_token == "" or my_user_id == 0:
		status_label.text = "تعذر إنشاء جلسة دخول."
		return
	save_session()
	show_users()

func show_users() -> void:
	page = "users"
	active_user_id = 0
	var root := make_background()
	var top := HBoxContainer.new()
	var logout := button("خروج")
	logout.custom_minimum_size = Vector2(150, 62)
	logout.pressed.connect(logout_now)
	top.add_child(logout)
	var heading := title("المستخدمون", 38)
	heading.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top.add_child(heading)
	root.add_child(top)
	var me := Label.new()
	me.text = "مرحبًا، " + my_username
	me.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	me.modulate = Color("28d6dd")
	root.add_child(me)
	status_label = Label.new()
	status_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	status_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	root.add_child(status_label)
	var scroll := ScrollContainer.new()
	scroll.size_flags_vertical = Control.SIZE_EXPAND_FILL
	var list := VBoxContainer.new()
	list.name = "UsersList"
	list.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	list.add_theme_constant_override("separation", 12)
	scroll.add_child(list)
	root.add_child(scroll)
	poll_timer.start()
	await refresh_users()

func refresh_users() -> void:
	if page != "users" or session_token == "": return
	var r = await api_rpc("marboua_list_users", {"p_session": session_token})
	if not r.ok:
		status_label.text = r.error
		if "الجلسة" in r.error: logout_now()
		return
	status_label.text = ""
	var list := find_child("UsersList", true, false) as VBoxContainer
	if list == null: return
	for c in list.get_children(): c.queue_free()
	for u in r.data:
		if int(u.get("user_id", 0)) == my_user_id: continue
		var uname := str(u.get("username", "مستخدم"))
		var b := button(uname)
		var uid := int(u.get("user_id", 0))
		b.pressed.connect(func(): show_chat(uid, uname))
		list.add_child(b)
	if list.get_child_count() == 0:
		var empty := Label.new()
		empty.text = "ما فيش مستخدمين غيرك توا."
		empty.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		list.add_child(empty)

func show_chat(uid: int, uname: String) -> void:
	page = "chat"
	active_user_id = uid
	active_username = uname
	var root := make_background()
	var top := HBoxContainer.new()
	var back := button("رجوع")
	back.custom_minimum_size = Vector2(140, 62)
	back.pressed.connect(show_users)
	top.add_child(back)
	var heading := title(uname, 36)
	heading.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top.add_child(heading)
	root.add_child(top)
	var note := Label.new()
	note.text = "الرسائل والملصقات تُحذف تلقائيًا بعد 24 ساعة"
	note.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	note.modulate = Color("96a0ba")
	note.add_theme_font_size_override("font_size", 20)
	root.add_child(note)
	status_label = Label.new()
	status_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	status_label.modulate = Color("ffcf70")
	root.add_child(status_label)
	var scroll := ScrollContainer.new()
	scroll.name = "MessagesScroll"
	scroll.size_flags_vertical = Control.SIZE_EXPAND_FILL
	var messages := VBoxContainer.new()
	messages.name = "MessagesList"
	messages.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	messages.add_theme_constant_override("separation", 10)
	scroll.add_child(messages)
	root.add_child(scroll)
	var sendbar := HBoxContainer.new()
	var input := line("اكتب رسالة...")
	input.name = "MessageInput"
	input.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	sendbar.add_child(input)
	var sticker := button("☺")
	sticker.custom_minimum_size = Vector2(82, 72)
	sticker.pressed.connect(func(): await send_message("sticker_01", "sticker"))
	sendbar.add_child(sticker)
	var send := button("إرسال")
	send.custom_minimum_size = Vector2(140, 72)
	sendbar.add_child(send)
	var send_action := func():
		if input.text.strip_edges() != "":
			await send_message(input.text.strip_edges(), "text")
			input.text = ""
	send.pressed.connect(send_action)
	input.text_submitted.connect(func(_t): await send_action.call())
	root.add_child(sendbar)
	poll_timer.start()
	await refresh_messages()

func send_message(content: String, kind: String) -> void:
	if active_user_id == 0: return
	var r = await api_rpc("marboua_send_message", {"p_session": session_token, "p_to_user": active_user_id, "p_content": content, "p_kind": kind})
	if not r.ok:
		status_label.text = r.error
		return
	status_label.text = ""
	await refresh_messages()

func refresh_messages() -> void:
	if page != "chat" or active_user_id == 0: return
	var r = await api_rpc("marboua_get_messages", {"p_session": session_token, "p_other_user": active_user_id})
	if not r.ok:
		status_label.text = r.error
		if "الجلسة" in r.error: logout_now()
		return
	status_label.text = ""
	var list := find_child("MessagesList", true, false) as VBoxContainer
	if list == null: return
	for c in list.get_children(): c.queue_free()
	for m in r.data:
		var l := Label.new()
		var own := int(m.get("from_user", 0)) == my_user_id
		var kind := str(m.get("kind", "text"))
		l.text = "☺  ملصق" if kind == "sticker" else str(m.get("content", ""))
		l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		l.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT if own else HORIZONTAL_ALIGNMENT_LEFT
		l.modulate = Color("a995ff") if own else Color("28d6dd")
		l.add_theme_font_size_override("font_size", 28)
		list.add_child(l)
	await get_tree().process_frame
	var scroll := find_child("MessagesScroll", true, false) as ScrollContainer
	if scroll:
		scroll.scroll_vertical = int(scroll.get_v_scroll_bar().max_value)

func _on_poll() -> void:
	if page == "users": await refresh_users()
	elif page == "chat": await refresh_messages()

func save_session() -> void:
	var cfg := ConfigFile.new()
	cfg.set_value("session", "token", session_token)
	cfg.set_value("session", "user_id", my_user_id)
	cfg.set_value("session", "username", my_username)
	cfg.save(SESSION_FILE)

func load_session() -> bool:
	var cfg := ConfigFile.new()
	if cfg.load(SESSION_FILE) != OK: return false
	session_token = str(cfg.get_value("session", "token", ""))
	my_user_id = int(cfg.get_value("session", "user_id", 0))
	my_username = str(cfg.get_value("session", "username", ""))
	return session_token != "" and my_user_id > 0

func logout_now() -> void:
	session_token = ""
	my_user_id = 0
	my_username = ""
	var cfg := ConfigFile.new()
	cfg.save(SESSION_FILE)
	show_auth()

func api_rpc(function_name: String, payload: Dictionary) -> Dictionary:
	var req := HTTPRequest.new()
	add_child(req)
	var headers := PackedStringArray(["Content-Type: application/json", "apikey: " + API_KEY, "Authorization: Bearer " + API_KEY])
	var url := API_URL.rstrip("/") + "/rest/v1/rpc/" + function_name
	var err := req.request(url, headers, HTTPClient.METHOD_POST, JSON.stringify(payload))
	if err != OK:
		req.queue_free()
		return {"ok": false, "error": "تعذر الاتصال بالسيرفر."}
	var result = await req.request_completed
	req.queue_free()
	var code := int(result[1])
	var text := (result[3] as PackedByteArray).get_string_from_utf8()
	var parsed = JSON.parse_string(text)
	if code < 200 or code >= 300:
		var msg := "حدث خطأ في السيرفر."
		if parsed is Dictionary: msg = str(parsed.get("message", msg))
		return {"ok": false, "error": msg}
	return {"ok": true, "data": parsed}
