# FCP 底栏像素图标

三个 24dp 单色矢量图（12×12 像素网格，每格 2 单位，`#FF000000` 填充），交给 `BottomNavigationView` 的选中/未选中着色即可。

| 文件 | Tab | 图案 |
|---|---|---|
| `ic_nav_versions.xml` | 版本 | 像素草方块（实心草层 + 下垂草边 + 镂空泥土斑点） |
| `ic_nav_accounts.xml` | 账号 | 方块人头（发际线、眼睛、嘴镂空，通用造型） |
| `ic_nav_about.xml` | 关于 | 像素圆形里镂空的 `i` |

预览：`nav-icons-preview.png`（灰 #49454F / 选中 #1B5E20，下排为 24px 实际大小）。`gen.py` 可重新生成 XML 和预览。

## 怎么用

1. 把三个 XML 复制到 `android/src/main/res/drawable/`。
2. 改 `android/src/main/res/menu/bottom_nav.xml`（PR #10，tip `5ec2d9a`）：

```diff
-        android:icon="@android:drawable/ic_menu_save"          <!-- 第 5 行 -->
+        android:icon="@drawable/ic_nav_versions"
-        android:icon="@android:drawable/ic_menu_myplaces"      <!-- 第 9 行 -->
+        android:icon="@drawable/ic_nav_accounts"
-        android:icon="@android:drawable/ic_menu_info_details"  <!-- 第 13 行 -->
+        android:icon="@drawable/ic_nav_about"
```

底栏是 XML 的 `BottomNavigationView`（`activity_main.xml` 第 21–25 行，`app:menu="@menu/bottom_nav"`），不是 Compose，所以不用改 Kotlin。默认 `itemIconTint` 会自动着色。
