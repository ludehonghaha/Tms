package socket

import (
	"fmt"
	"os"
	"path/filepath"

	"github.com/go-gost/x/config"
)

// saveConfig 将当前 Runtime 配置原子落盘。
//
// 旧实现直接 os.Create("gost.json")：Create 会先截断原文件，如果进程在 Write
// 中途退出/磁盘写满，下一次启动拿到的就是半截 JSON。动态下发越频繁，这个窗口越明显。
//
// 现在先完整写入同目录临时文件，fsync 成功后再 rename 覆盖。Linux/Unix 同文件系统
// rename 是原子的，因此任何时刻磁盘上至少保留一份完整配置；覆盖前再保留一份
// gost.json.bak，便于异常下发时快速回滚到上一版。
func saveConfig() error {
	file := "gost.json"
	dir := filepath.Dir(file)
	base := filepath.Base(file)

	tmp, err := os.CreateTemp(dir, base+".tmp-*")
	if err != nil {
		return fmt.Errorf("创建 Runtime 临时配置失败: %w", err)
	}
	tmpName := tmp.Name()
	cleanup := func() {
		_ = tmp.Close()
		_ = os.Remove(tmpName)
	}

	if err := tmp.Chmod(0600); err != nil {
		cleanup()
		return fmt.Errorf("设置 Runtime 临时配置权限失败: %w", err)
	}

	if err := config.Global().Write(tmp, "json"); err != nil {
		cleanup()
		return fmt.Errorf("序列化 Runtime 配置失败: %w", err)
	}

	if err := tmp.Sync(); err != nil {
		cleanup()
		return fmt.Errorf("同步 Runtime 临时配置失败: %w", err)
	}

	if err := tmp.Close(); err != nil {
		_ = os.Remove(tmpName)
		return fmt.Errorf("关闭 Runtime 临时配置失败: %w", err)
	}

	// 先保留上一版。备份失败不覆盖现有 gost.json，避免在没有回滚点时继续下发。
	if old, err := os.ReadFile(file); err == nil && len(old) > 0 {
		if err := writeAtomic(filepath.Join(dir, base+".bak"), old, 0600); err != nil {
			_ = os.Remove(tmpName)
			return fmt.Errorf("备份上一版 Runtime 配置失败: %w", err)
		}
	}

	if err := os.Rename(tmpName, file); err != nil {
		_ = os.Remove(tmpName)
		return fmt.Errorf("原子替换 Runtime 配置失败: %w", err)
	}

	// 尽力同步目录项，确保 rename 在意外断电后也更可靠。部分文件系统不支持目录 Sync，
	// 不因此判定本次下发失败，因为 gost.json 已经原子替换完成。
	if d, err := os.Open(dir); err == nil {
		_ = d.Sync()
		_ = d.Close()
	}

	return nil
}

func writeAtomic(path string, data []byte, mode os.FileMode) error {
	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, filepath.Base(path)+".tmp-*")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	ok := false
	defer func() {
		_ = tmp.Close()
		if !ok {
			_ = os.Remove(tmpName)
		}
	}()

	if err := tmp.Chmod(mode); err != nil {
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		return err
	}
	if err := tmp.Sync(); err != nil {
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmpName, path); err != nil {
		return err
	}
	ok = true
	return nil
}
