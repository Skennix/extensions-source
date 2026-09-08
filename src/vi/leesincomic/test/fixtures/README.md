# Fixture — leesincomic.com

**Chụp 2026-09-08** bằng UA trình duyệt. Fixture cũ đi thì test là test dối — chụp lại và sửa ngày
này khi site đổi giao diện.

Chỉ lưu HTML, **không lưu ảnh**.

| File | `div.li_truyen` | `div.detail` | Vai |
|---|---|---|---|
| `danh-sach-trang-1.html` | 48 | 50 | `/top-thang.html` |
| `danh-sach-trang-giua.html` | 48 | 50 | `?page=3` — giao với trang 1 phải = 0 |
| `danh-sach-trang-cuoi.html` | **0** | 50 | `?page=30` — thứ DUY NHẤT chứng minh `hasNextPage=false` |
| `tim-kiem-co-ket-qua.html` | 48 | 50 | `/tim-kiem.html?key=ngu` |
| `tim-kiem-rong.html` | **0** | 50 | `key=zzzqqq` — search rỗng là HỢP LỆ, trả rỗng chứ không ném |
| `trang-bo.html` | 0 | 0 | `/truyen-tranh/nguc-tu.html` — 20 chương, tất cả `Free` |
| `trang-chuong.html` | 0 | 0 | `/truyen-tranh/nguc-tu/20-end.html` — 33 ảnh `tachserver.site` |

## 🔴 Vì sao cột `div.detail` mới là cột đáng nhìn

`div.detail` là **widget** có mặt trên MỌI trang, kể cả hai trang "rỗng". Đó là lý do hai fixture rỗng
vẫn mang **50** mục.

⇒ Chúng là **mẫu cố tình dương cho phép cắt phạm vi**: bỏ cắt phạm vi thì hai fixture rỗng trả **50**
thay vì **0**, và test đỏ ngay. Chụp một trang "đẹp" không widget thì phép phá đó **không làm ca nào
đỏ** — guard xanh vì lý do sai.

Đo 2026-09-08 (BACKLOG (146) của repo `manga-crawler`): dùng nhầm `div.detail` thì mọi lượt duyệt trả
cùng 19 bộ, và **tìm kiếm không bao giờ ra rỗng**.
