#!/usr/bin/env python3
"""Builds the synthetic memory-bench datasets from the label tables below.

Writes lab/memory/datasets/fact_extraction.jsonl and lab/memory/datasets/compaction.jsonl.
All people, places, numbers and codes are invented. Run with: python3 -I lab/memory/scripts/build_datasets.py
"""
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]

# (id, message, [(kind, fact)], tags). A gold fact is the exact first-person text the app would store.
FACT_CASES = [
    # Phrasings the regex detector already covers.
    ("fx-001", "我对芒果过敏，推荐点夏天的水果吧", [("Allergy", "我对芒果过敏")], ["zh", "regex", "mixed"]),
    ("fx-002", "我儿子对鸡蛋过敏。", [("Allergy", "我儿子对鸡蛋过敏")], ["zh", "regex"]),
    ("fx-003", "我家在杭州市西湖区文三路", [("HomeLocation", "我家在杭州市西湖区文三路")], ["zh", "regex"]),
    ("fx-004", "我住在成都高新区", [("HomeLocation", "我住在成都高新区")], ["zh", "regex"]),
    ("fx-005", "我在星河科技上班", [("WorkLocation", "我在星河科技上班")], ["zh", "regex"]),
    ("fx-006", "我老公叫陈明", [("RelationName", "我老公叫陈明")], ["zh", "regex"]),
    ("fx-007", "我妈妈的手机号是139-0000-1234", [("ContactPhone", "我妈妈的手机号是139-0000-1234")], ["zh", "regex"]),
    ("fx-008", "我不吃香菜", [("Diet", "我不吃香菜")], ["zh", "regex"]),
    ("fx-009", "我的生日是11月3日", [("Birthday", "我的生日是11月3日")], ["zh", "regex"]),
    ("fx-010", "I'm allergic to peanuts.", [("Allergy", "I'm allergic to peanuts")], ["en", "regex"]),
    ("fx-011", "I live in Seattle", [("HomeLocation", "I live in Seattle")], ["en", "regex"]),
    ("fx-012", "my husband's name is David", [("RelationName", "my husband's name is David")], ["en", "regex"]),
    ("fx-013", "I am vegan", [("Diet", "I am vegan")], ["en", "regex"]),
    ("fx-014", "我爸爸叫王建国", [("RelationName", "我爸爸叫王建国")], ["zh", "regex"]),
    ("fx-015", "我公司在深圳南山科技园", [("WorkLocation", "我公司在深圳南山科技园")], ["zh", "regex"]),
    ("fx-016", "My son's name is Leo", [("RelationName", "My son's name is Leo")], ["en", "regex"]),
    ("fx-017", "I work at Northwind Labs in Redmond", [("WorkLocation", "I work at Northwind Labs in Redmond")], ["en", "regex"]),
    # Kinds and phrasings only the model path can propose.
    ("fx-018", "I’m allergic to latex", [("Allergy", "I’m allergic to latex")], ["en", "model", "apostrophe"]),
    ("fx-019", "我的手机号是１３９００００５６７８", [("ContactPhone", "我的手机号是１３９００００５６７８")], ["zh", "model", "full-width"]),
    ("fx-020", "我喜欢喝不加糖的美式咖啡", [("Preference", "我喜欢喝不加糖的美式咖啡")], ["zh", "model"]),
    ("fx-021", "我讨厌坐红眼航班", [("Preference", "我讨厌坐红眼航班")], ["zh", "model"]),
    ("fx-022", "我每天晚上十一点前睡觉", [("Routine", "我每天晚上十一点前睡觉")], ["zh", "model"]),
    ("fx-023", "我每周六上午去游泳", [("Routine", "我每周六上午去游泳")], ["zh", "model"]),
    ("fx-024", "我是一名产品经理", [("Occupation", "我是一名产品经理")], ["zh", "model"]),
    ("fx-025", "我是牙科护士", [("Occupation", "我是牙科护士")], ["zh", "model"]),
    ("fx-026", "我海鲜过敏", [("Allergy", "我海鲜过敏")], ["zh", "model"]),
    ("fx-027", "我吃素已经五年了", [("Diet", "我吃素已经五年了")], ["zh", "model"]),
    ("fx-028", "我住南京鼓楼区", [("HomeLocation", "我住南京鼓楼区")], ["zh", "model"]),
    ("fx-029", "我在市一医院当护士", [("Occupation", "我在市一医院当护士")], ["zh", "model"]),
    ("fx-030", "I work as a nurse at St. Mary's", [("Occupation", "I work as a nurse at St. Mary's")], ["en", "model"]),
    ("fx-031", "I usually go running at 6am", [("Routine", "I usually go running at 6am")], ["en", "model"]),
    ("fx-032", "I prefer window seats on flights", [("Preference", "I prefer window seats on flights")], ["en", "model"]),
    ("fx-033", "I'm a high school math teacher", [("Occupation", "I'm a high school math teacher")], ["en", "model"]),
    ("fx-034", "my daughter Lily was born on May 2", [("Birthday", "my daughter Lily was born on May 2")], ["en", "model"]),
    ("fx-035", "我女儿的生日在8月15号", [("Birthday", "我女儿的生日在8月15号")], ["zh", "model"]),
    ("fx-036", "我太太的名字叫林雪", [("RelationName", "我太太的名字叫林雪")], ["zh", "model"]),
    ("fx-037", "我平时不喝酒", [("Diet", "我平时不喝酒")], ["zh", "model"]),
    ("fx-038", "我习惯用微信支付", [("Preference", "我习惯用微信支付")], ["zh", "model"]),
    ("fx-039", "我上班在望京SOHO", [("WorkLocation", "我上班在望京SOHO")], ["zh", "model"]),
    ("fx-040", "I don't eat pork", [("Diet", "I don't eat pork")], ["en", "model"]),
    ("fx-041", "I always take the subway to work", [("Routine", "I always take the subway to work")], ["en", "model"]),
    ("fx-042", "my mom's birthday is June 9", [("Birthday", "my mom's birthday is June 9")], ["en", "model"]),
    ("fx-043", "我周末一般在家带孩子", [("Routine", "我周末一般在家带孩子")], ["zh", "model"]),
    ("fx-044", "我是素食主义者", [("Diet", "我是素食主义者")], ["zh", "model"]),
    # A fact next to requests, questions or time-bound clauses (distractors).
    ("fx-050", "顺便说下，我对青霉素过敏，帮我看看这个感冒药能不能吃", [("Allergy", "我对青霉素过敏")], ["zh", "regex", "mixed"]),
    ("fx-051", "我是程序员，帮我写个周报模板", [("Occupation", "我是程序员")], ["zh", "model", "mixed"]),
    ("fx-052", "我每天早上七点出门，帮我设个六点半的闹钟", [("Routine", "我每天早上七点出门")], ["zh", "model", "mixed"]),
    ("fx-053", "我喜欢安静的餐厅，附近有推荐吗？", [("Preference", "我喜欢安静的餐厅")], ["zh", "model", "mixed"]),
    ("fx-054", "我住在苏州工业园区，今天想去上海玩", [("HomeLocation", "我住在苏州工业园区")], ["zh", "regex", "mixed"]),
    ("fx-055", "I live in Boston and I'm allergic to cats",
     [("HomeLocation", "I live in Boston"), ("Allergy", "I'm allergic to cats")], ["en", "model", "mixed", "multi"]),
    ("fx-056", "我老婆叫刘洋，她对猫毛过敏", [("RelationName", "我老婆叫刘洋")], ["zh", "regex", "mixed"]),
    ("fx-057", "帮我订一张明天去北京的机票，我一般坐靠窗的位置", [("Preference", "我一般坐靠窗的位置")], ["zh", "model", "mixed"]),
    ("fx-058", "我在上海交通大学读研究生，帮我查下图书馆开放时间", [("Occupation", "我在上海交通大学读研究生")], ["zh", "model", "mixed"]),
    ("fx-059", "我平时开车上班，帮我看看现在的路况", [("Routine", "我平时开车上班")], ["zh", "model", "mixed"]),
    ("fx-060", "对了，我女儿叫朵朵，今年上幼儿园", [("RelationName", "我女儿叫朵朵")], ["zh", "regex", "mixed"]),
    ("fx-061", "My wife is allergic to shellfish, so please skip seafood places",
     [("Allergy", "My wife is allergic to shellfish")], ["en", "regex", "mixed"]),
    ("fx-062", "I'm a software engineer, can you suggest a good keyboard?", [("Occupation", "I'm a software engineer")],
     ["en", "model", "mixed"]),
    ("fx-063", "我每天中午都在公司食堂吃饭，帮我算算这个月饭钱", [("Routine", "我每天中午都在公司食堂吃饭")], ["zh", "model", "mixed"]),
    ("fx-064", "我对酒精过敏，所以聚会别给我倒酒", [("Allergy", "我对酒精过敏")], ["zh", "regex", "mixed"]),
    ("fx-065", "我是一名小学老师，我每周三下午有教研会", [("Occupation", "我是一名小学老师"), ("Routine", "我每周三下午有教研会")],
     ["zh", "model", "mixed", "multi"]),
    # Nothing durable about the user: questions, hypotheticals, hedges, negations, third parties,
    # temporary or past states, quotes, requests, explicit commands and adversarial look-alikes.
    ("fx-100", "我对什么过敏来着？", [], ["zh", "negative", "question"]),
    ("fx-101", "如果我住在北京，通勤要多久", [], ["zh", "negative", "hypothetical"]),
    ("fx-102", "我可能对海鲜过敏", [], ["zh", "negative", "hedge"]),
    ("fx-103", "我对花粉不过敏", [], ["zh", "negative", "negated"]),
    ("fx-104", "我今天不想吃辣的", [], ["zh", "negative", "temporary"]),
    ("fx-105", "我朋友住在广州", [], ["zh", "negative", "third-party"]),
    ("fx-106", "我同事是一名律师", [], ["zh", "negative", "third-party"]),
    ("fx-107", "我以前住在武汉", [], ["zh", "negative", "past"]),
    ("fx-108", "我小时候很喜欢吃糖", [], ["zh", "negative", "past"]),
    ("fx-109", "我现在在机场", [], ["zh", "negative", "temporary"]),
    ("fx-110", "帮我把“我住在上海”翻译成英文", [], ["zh", "negative", "quote"]),
    ("fx-111", "他说：我是医生", [], ["zh", "negative", "quote"]),
    ("fx-112", "记住我喜欢喝茶", [], ["zh", "negative", "explicit-command"]),
    ("fx-113", "忘记我住在哪里", [], ["zh", "negative", "explicit-command"]),
    ("fx-114", "明天早上八点提醒我开会", [], ["zh", "negative", "request"]),
    ("fx-115", "附近有什么好吃的川菜馆", [], ["zh", "negative", "question"]),
    ("fx-116", "我最近在减肥", [], ["zh", "negative", "temporary"]),
    ("fx-117", "My friend is allergic to dogs", [], ["en", "negative", "third-party"]),
    ("fx-118", "If I lived in London I'd take the tube", [], ["en", "negative", "hypothetical"]),
    ("fx-119", "I'm not sure where I left my keys", [], ["en", "negative", "hedge"]),
    ("fx-120", "I'm flying to Tokyo tomorrow", [], ["en", "negative", "temporary"]),
    ("fx-121", "Can you tell me what time it is in Paris?", [], ["en", "negative", "question"]),
    ("fx-122", "我觉得这部电影还不错", [], ["zh", "negative", "opinion"]),
    ("fx-123", "我这周在出差", [], ["zh", "negative", "temporary"]),
    ("fx-124", "你是谁", [], ["zh", "negative", "question"]),
    ("fx-125", "给我老婆打电话", [], ["zh", "negative", "request"]),
    ("fx-126", "我上周去了一趟杭州", [], ["zh", "negative", "past"]),
    ("fx-127", "我要一杯拿铁", [], ["zh", "negative", "request"]),
    ("fx-128", "我刚才说错了", [], ["zh", "negative", "temporary"]),
    ("fx-129", "我们公司的同事都喜欢打羽毛球", [], ["zh", "negative", "third-party", "adversarial"]),
    ("fx-130", "假如我是老板，我会给大家放假", [], ["zh", "negative", "hypothetical"]),
    ("fx-131", "my boss lives in Shenzhen", [], ["en", "negative", "third-party"]),
    ("fx-132", "我想学游泳", [], ["zh", "negative", "wish"]),
    ("fx-133", "我打算下个月搬到深圳", [], ["zh", "negative", "plan"]),
    ("fx-134", "这个月我每天都加班", [], ["zh", "negative", "temporary", "adversarial"]),
    ("fx-135", "帮我给13800005678打个电话", [], ["zh", "negative", "request"]),
    ("fx-136", "我的快递单号是SF1234567890", [], ["zh", "negative", "one-time", "adversarial"]),
    ("fx-137", "我的验证码是482913", [], ["zh", "negative", "one-time", "adversarial"]),
    ("fx-138", "I need a taxi to the airport", [], ["en", "negative", "request"]),
    ("fx-139", "What's the weather like in Beijing this weekend?", [], ["en", "negative", "question"]),
    ("fx-140", "我昨天吃了海鲜，有点过敏", [], ["zh", "negative", "past"]),
    ("fx-141", "帮我搜一下“我是歌手”第几季最好看", [], ["zh", "negative", "quote"]),
    ("fx-142", "我老板说他对花生过敏", [], ["zh", "negative", "third-party"]),
    ("fx-143", "我暂时住在朋友家", [], ["zh", "negative", "temporary"]),
    ("fx-144", "Please translate \"I live in Paris\" into Chinese", [], ["en", "negative", "quote"]),
    ("fx-145", "我女朋友可能喜欢这条项链", [], ["zh", "negative", "hedge"]),
]

ZH_FILLER = [
    "我先把目前掌握的情况整理一下，方便你核对。",
    "以下信息来自刚才的查询结果，可能会有变化，最好出发前再确认一次。",
    "如果你有其他要求，比如时间或者预算上的调整，可以随时告诉我，我再帮你重新安排。",
    "这一步我已经替你核对过相关页面上的说明，没有发现异常。",
]
EN_FILLER = [
    "Let me summarize what I found so you can double-check it.",
    "These details come from the search results just now and may change, so it is worth confirming before you go.",
    "If you want to adjust the time or budget, just tell me and I will look again.",
]


# Appended to every older assistant reply, so replies are as long as real ones and the heuristic
# summary (first 100 characters per message) has to drop text.
ZH_CLOSING = [
    "后面如果情况有变化，我会第一时间提醒你；你也可以随时让我重新查一遍，或者把这些信息发给家人一起看看，再决定下一步怎么做。",
    "这些内容我都已经核对过一遍，不过页面上的信息偶尔会更新，真正操作之前最好再看一眼确认；如果还有不清楚的地方，直接问我就好。",
]
EN_CLOSING = [
    "If anything changes I will let you know right away, and you can ask me to check again at any time or share these details "
    "with someone before deciding what to do next.",
    "I have checked these details once already, but pages like this are sometimes updated, so take another look before you "
    "act on them, and ask me if anything is unclear.",
]


def fill(lang, *indexes):
    table = ZH_FILLER if lang == "zh" else EN_FILLER
    return "".join(table[i] for i in indexes) if lang == "zh" else " ".join(table[i] for i in indexes) + " "


def user(text):
    return {"role": "user", "text": text}


def assistant(text):
    return {"role": "assistant", "text": text}


def tool(payload):
    return {"role": "assistant", "kind": "ToolResult", "privacy": "LocalOnly",
            "text": json.dumps(payload, ensure_ascii=False, separators=(",", ":"))}


def screen(text):
    return {"role": "assistant", "kind": "ScreenObservation", "privacy": "LocalOnly", "text": text}


ZH_TAIL = [user("好的，谢谢"), assistant("不客气，有需要再叫我。"), user("先这样吧"), assistant("好的。")]
EN_TAIL = [user("Thanks"), assistant("You're welcome."), user("That's all for now"), assistant("Okay.")]

# (id, lang, middle messages, must-keep strings). No must-keep string occurs in a message the compactor
# keeps verbatim (user turns, tool failures, the latest screen), so it survives only through the summary.
COMPACTION_CASES = [
    ("cx-01", "zh", [
        user("帮我查一下周五从北京去上海的高铁"),
        assistant("好的，我查了周五北京南站到上海虹桥的车次。" + fill("zh", 0, 1) +
                  "最早的是G1，07:00发车，11:29到达；比较推荐的是G13，08:15发车，13:03到达，二等座553元，目前还有余票。"),
        tool({"tool": "train_search", "date": "周五", "from": "北京南", "to": "上海虹桥", "results": [
            {"train": "G1", "depart": "07:00", "arrive": "11:29", "second_class": "604元", "left": "少量"},
            {"train": "G13", "depart": "08:15", "arrive": "13:03", "second_class": "553元", "left": "有"}],
            "note": "票价以12306为准"}),
        user("就订你推荐的那班吧"),
        assistant(fill("zh", 3, 0) + "已经帮你提交了G13的订单，座位是06车12F靠窗，订单号E92K7Q，请在30分钟内完成支付。"),
        user("好的，我稍后支付"),
        assistant(fill("zh", 2) + "没问题，支付截止时间是今天18:40，过期订单会自动取消，到时候我再提醒你。"),
    ], ["G13", "08:15", "553元", "06车12F", "E92K7Q", "18:40"]),
    ("cx-02", "zh", [
        user("帮我找一家望京附近适合四个人聚餐的餐厅"),
        assistant("我在望京附近筛选了几家评分较高、适合多人聚餐的餐厅。" + fill("zh", 1, 2) +
                  "综合距离和口碑，比较推荐“山海小馆（望京店）”，人均128元，评分4.7，地址是阜通东大街6号院3号楼2层。"),
        tool({"tool": "restaurant_search", "area": "望京", "party_size": 4, "results": [
            {"name": "山海小馆（望京店）", "avg_price": "128元", "rating": 4.7, "address": "阜通东大街6号院3号楼2层",
             "phone": "010-6478-2231"},
            {"name": "云上川菜", "avg_price": "96元", "rating": 4.5, "address": "望京西路41号"}]}),
        user("就订你推荐的那家，周六晚上"),
        assistant(fill("zh", 3, 0) + "已经为你预订了周六18:30的四人桌，预订号R5520，餐厅会保留座位15分钟，如需变更可以拨打010-6478-2231。"),
    ], ["山海小馆", "128元", "阜通东大街6号院", "18:30", "R5520", "010-6478-2231"]),
    ("cx-03", "zh", [
        user("我的快递到哪了"),
        assistant("我查到你最近有一个顺丰快递正在派送中。" + fill("zh", 0, 1) +
                  "运单号SF7781203345，目前在北京顺义转运中心，预计周四送达。"),
        tool({"tool": "package_track", "carrier": "顺丰速运", "waybill": "SF7781203345", "events": [
            {"time": "11-18 09:12", "place": "深圳宝安", "status": "已揽收"},
            {"time": "11-19 22:40", "place": "北京顺义转运中心", "status": "运输中"}],
            "eta": "周四", "pickup_point": "小区东门菜鸟驿站", "pickup_code": "6-2-1187"}),
        user("到了会放驿站吗"),
        assistant(fill("zh", 3, 2) + "是的，送达后会放在小区东门菜鸟驿站，取件码是6-2-1187，驿站营业到晚上21:00。"),
    ], ["SF7781203345", "顺义转运中心", "菜鸟驿站", "6-2-1187", "21:00"]),
    ("cx-04", "zh", [
        user("帮我看看日历里下周的项目评审会"),
        tool({"tool": "calendar_query", "range": "下周", "events": [
            {"title": "周会", "time": "11月24日 10:00-10:30", "room": "A105"},
            {"title": "项目评审会", "time": "11月26日 14:00-15:30", "room": "B302会议室", "online": "腾讯会议 618-224-907",
             "organizer": "周婷", "attendees": 12}]}),
        assistant(fill("zh", 0, 3) + "下周三11月26日14:00有项目评审会，地点在B302会议室，线上可以用腾讯会议618-224-907加入，组织者是周婷。"),
        user("帮我设个提前半小时的提醒"),
        assistant(fill("zh", 2) + "已经设置好提醒，会在11月26日13:30通知你，提醒内容是“项目评审会，B302会议室”。"),
    ], ["11月26日", "14:00", "B302", "618-224-907", "周婷", "13:30"]),
    ("cx-05", "zh", [
        user("我的航班改签了吗"),
        tool({"tool": "flight_status", "flight": "MU5137", "date": "11-22", "route": "上海虹桥-北京首都", "status": "已改签",
              "new_departure": "19:55", "gate": "C17", "seat": "32A", "change_fee": "200元", "baggage": "20kg"}),
        assistant(fill("zh", 0, 1) + "已经改签成功，新的航班是MU5137，11月22日19:55从上海虹桥起飞，登机口C17，座位32A，改签费200元已从原支付方式扣除。"),
        user("行李额度有变化吗"),
        assistant(fill("zh", 3, 2) + "行李额度没有变化，仍然是20kg托运额度，随身行李不超过5kg。"),
    ], ["MU5137", "19:55", "C17", "32A", "200元", "20kg"]),
    ("cx-06", "zh", [
        user("帮我订下周去上海出差的酒店"),
        assistant("我按你常用的价位找了外滩附近的几家酒店。" + fill("zh", 1, 2) +
                  "推荐全季酒店（外滩店），每晚468元，含双早，距离你开会的地方步行约10分钟。"),
        tool({"tool": "hotel_book", "hotel": "全季酒店（外滩店）", "check_in": "11月21日", "check_out": "11月23日",
              "room": "高级大床房", "price": "468元/晚", "confirmation": "HZ88310", "breakfast": "7:00-10:00",
              "address": "四川中路99号"}),
        user("早餐几点"),
        assistant(fill("zh", 0, 3) + "早餐时间是7:00到10:00，在二楼餐厅。你的确认号是HZ88310，入住时报手机号即可。"),
    ], ["全季酒店", "468元", "11月21日", "HZ88310", "7:00", "四川中路99号"]),
    ("cx-07", "zh", [
        user("帮我挂个皮肤科的号"),
        assistant(fill("zh", 0, 1) + "我查了协和医院皮肤科的号源，周五上午还有余号，挂号费50元。"),
        tool({"tool": "hospital_booking", "hospital": "协和医院（东单院区）", "department": "皮肤科", "doctor": "李晓梅 副主任医师",
              "time": "周五 9:40", "queue_no": "A027", "fee": "50元",
              "notes": "请携带医保卡和身份证，提前20分钟到二层自助机取号"}),
        user("就挂这个"),
        assistant(fill("zh", 3, 2) + "已为你预约周五9:40李晓梅医生的号，就诊序号A027，记得带医保卡和身份证，提前20分钟到二层自助机取号。"),
    ], ["李晓梅", "9:40", "A027", "50元", "医保卡", "20分钟"]),
    ("cx-08", "zh", [
        user("帮我比较一下索尼降噪耳机的价格"),
        tool({"tool": "price_compare", "product": "索尼 WH-1000XM5", "offers": [
            {"shop": "京东自营", "price": "2299元", "warranty": "2年"},
            {"shop": "拼多多百亿补贴", "price": "2099元", "warranty": "1年"},
            {"shop": "天猫旗舰店", "price": "2399元", "gift": "收纳包"}]}),
        assistant(fill("zh", 1, 0) + "WH-1000XM5目前最便宜的是拼多多百亿补贴2099元，但保修只有1年；京东自营2299元，保修2年。"),
        user("有没有优惠券"),
        assistant(fill("zh", 3, 2) + "京东自营现在可以领满2000减150的券，用券后是2149元，比拼多多只贵50元，还多一年保修。"),
    ], ["WH-1000XM5", "2099元", "2299元", "2149元", "满2000减150"]),
    ("cx-09", "en", [
        user("Find me a rental car at SFO for next weekend"),
        assistant("I looked at rental options at San Francisco International for next weekend. " + fill("en", 0, 1) +
                  "The best value is a Toyota Corolla from Hertz at $64/day, with free cancellation up to 24 hours before pickup."),
        tool({"tool": "car_rental", "location": "SFO Terminal 2", "company": "Hertz", "car": "Toyota Corolla",
              "pickup": "Sat 10:30", "return": "Mon 09:00", "price": "$64/day", "confirmation": "7HX42Q",
              "insurance": "basic included"}),
        user("Book it"),
        assistant(fill("en", 2, 0) + "Done. Your confirmation number is 7HX42Q. Pick up the car on Saturday at 10:30 at "
                  "SFO Terminal 2 and return it by Monday 09:00."),
    ], ["Toyota Corolla", "$64/day", "7HX42Q", "10:30", "Terminal 2", "09:00"]),
    ("cx-10", "en", [
        user("How do I make banana bread?"),
        assistant("Here is a simple banana bread recipe that works well for beginners. " + fill("en", 0) +
                  "You need 3 ripe bananas, 2 cups flour, 1 teaspoon baking soda, 1/3 cup melted butter and 3/4 cup sugar."),
        user("What temperature should the oven be?"),
        assistant(fill("en", 1, 2) + "Preheat the oven to 175°C (350°F) and bake for 55 to 60 minutes, until a toothpick comes out clean."),
        user("Can I freeze it?"),
        assistant(fill("en", 2, 0) + "Yes. Wrap it tightly and freeze it for up to 3 months; thaw it overnight in the fridge."),
    ], ["3 ripe bananas", "2 cups flour", "175°C", "55 to 60 minutes", "3 months"]),
    ("cx-11", "zh", [
        user("帮我看看家里的Wi-Fi是哪个"),
        screen("设置 > WLAN | 开关：已开启 | 已连接：Home-5G-2207 | 信号强度：强 | 安全性：WPA3 | 频段：5GHz | "
               "其他网络：Guest-2207、ChinaNet-K8sd、TP-LINK_93A1 | 底部按钮：添加网络、网络加速"),
        assistant(fill("zh", 0, 3) + "你当前连接的网络是Home-5G-2207，安全类型WPA3，频段5GHz。我再打开网络详情看看。"),
        screen("网络详情 | Home-5G-2207 | 分享密码：点按二维码查看 | IP 地址：192.168.31.24 | 路由器：192.168.31.1 | MAC：随机"),
        user("密码呢"),
        assistant(fill("zh", 2) + "密码需要你在屏幕上点按二维码后验证身份才能查看。路由器管理地址是192.168.31.1，访客网络是Guest-2207。"),
    ], ["WPA3", "5GHz", "Guest-2207", "验证身份"]),
    ("cx-12", "zh", [
        user("帮我查一下这个月的电费"),
        tool({"tool": "bill_query", "type": "电费", "account": "1029384756", "period": "2026年10月", "amount": "386.40元",
              "due": "11月25日", "usage": "612度", "tier": "第二阶梯"}),
        assistant(fill("zh", 0, 1) + "你家10月的电费是386.40元，用电612度，已经进入第二阶梯，缴费截止日期是11月25日。"),
        user("帮我用支付宝缴了"),
        assistant(fill("zh", 3, 2) + "已经跳转到支付宝缴费页面，户号1029384756已自动填好，金额386.40元，需要你在支付宝里确认付款。"),
    ], ["386.40元", "612度", "第二阶梯", "11月25日", "1029384756"]),
]


def write_jsonl(path, records):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as out:
        for record in records:
            out.write(json.dumps(record, ensure_ascii=False) + "\n")


def main():
    ids = [case[0] for case in FACT_CASES]
    assert len(ids) == len(set(ids)), "duplicate fact case id"
    write_jsonl(ROOT / "datasets" / "fact_extraction.jsonl", [
        {"id": case_id, "message": message, "gold": [{"kind": k, "fact": f} for k, f in gold], "tags": tags}
        for case_id, message, gold, tags in FACT_CASES
    ])
    records = []
    for case_id, lang, middle, must_keep in COMPACTION_CASES:
        closing = ZH_CLOSING if lang == "zh" else EN_CLOSING
        chat_replies = [m for m in middle if m["role"] == "assistant" and "kind" not in m]
        for i, message in enumerate(chat_replies):
            message["text"] += ("" if lang == "zh" else " ") + ("" if lang == "zh" else " ").join(closing[i % 2:] + closing[:i % 2])
        screens = [m for m in middle if m.get("kind") == "ScreenObservation"]
        verbatim = [m for m in middle if m["role"] == "user" or m.get("kind") == "ToolFailure"] + screens[-1:]
        for item in must_keep:
            assert any(item in m["text"] for m in middle), f"{case_id}: {item} missing"
            assert not any(item in m["text"] for m in verbatim), f"{case_id}: {item} is kept verbatim"
        records.append({"id": case_id, "lang": lang, "messages": middle + (ZH_TAIL if lang == "zh" else EN_TAIL),
                        "must_keep": must_keep})
    write_jsonl(ROOT / "datasets" / "compaction.jsonl", records)
    print(f"fact cases: {len(FACT_CASES)}, compaction cases: {len(records)}")


if __name__ == "__main__":
    main()
