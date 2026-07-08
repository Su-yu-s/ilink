// iLink Particle System — 按需加载，仅用于首页/登录/注册页
// ========================================
// Enhanced Particle System with Mouse Interaction
// 支持参数化配置，可适配首页（密集、强连线）与登录/注册页（稀疏、柔和、多色）
// ========================================
class ParticleSystem {
    constructor(canvasId, options = {}) {
        // 仅在明确标记 data-particles="true" 的页面上初始化（首页 / 登录 / 注册）
        if (!document.body || document.body.getAttribute('data-particles') !== 'true') {
            console.debug('[ParticleSystem] 未启用（data-particles 未设置）');
            return;
        }
        if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            console.debug('[ParticleSystem] 已禁用（用户偏好减少动画）');
            return;
        }

        this.canvas = document.getElementById(canvasId);
        if (!this.canvas) {
            console.debug('[ParticleSystem] 未找到 canvas 元素:', canvasId);
            return;
        }

        // 防重复初始化：若同一 canvas 已托管实例，先销毁旧实例，避免多个 RAF 并行
        const existing = ParticleSystem._instances && ParticleSystem._instances.get(this.canvas);
        if (existing) {
            existing.destroy();
        }
        ParticleSystem._instances = ParticleSystem._instances || new Map();
        ParticleSystem._instances.set(this.canvas, this);

        this.ctx = this.canvas.getContext('2d');
        this.particles = [];
        this.mouse = { x: null, y: null };
        this.rafId = null;
        this.time = 0;
        // 限制 DPR 上限，避免 4K 屏渲染负担过重
        this.dpr = Math.min(window.devicePixelRatio || 1, 2);
        this.resizeTimer = null;
        this.themeObserver = null;

        // 配置合并（向后兼容：不传 options 时保持原有视觉表现）
        this.opts = Object.assign({
            // 粒子数量：按视口面积自适应（每像素的粒子数）
            density: 0.00004,          // 1920×1080 ≈ 83 粒子，接近原固定 80
            minParticles: 30,
            maxParticles: 140,
            fixedCount: null,          // 设置后忽略 density，使用固定数量
            // 外观
            sizeRange: [2, 4.5],       // 粒子半径范围 [min, max]
            speedScale: 0.5,           // 基础速度倍率
            opacityRange: [0.3, 0.6],  // 基础透明度范围
            colors: null,              // null => 主题自适应（亮色蓝 / 暗色白）；['r,g,b', ...] => 多色随机
            // 连线
            connectDistance: 160,
            connectOpacity: 0.7,
            lineWidth: 1.2,
            // 鼠标交互
            mouseEnabled: true,
            mouseRadius: 150,
            mouseForce: 2,
            // 呼吸脉动
            pulse: true,
            pulseSpeed: 0.018,
            pulseAmount: 0.12
        }, options);

        this.connectionDistance = this.opts.connectDistance;
        this.connectionDistanceSq = this.connectionDistance * this.connectionDistance;
        this.gridCellSize = Math.ceil(this.connectionDistance * 1.05);
        this.isDarkMode = document.documentElement.getAttribute('data-theme') === 'dark';

        this.init();
        this.bindEvents();
        this.animate();
    }

    /** 解析当前主题下的粒子颜色集合 */
    resolveColors() {
        const colors = this.opts.colors;
        if (Array.isArray(colors) && colors.length) {
            return colors;
        }
        return this.isDarkMode ? ['255, 255, 255'] : ['59, 130, 246'];
    }

    /** 根据配置计算粒子数量（按视口面积自适应，带上下限） */
    computeCount() {
        if (this.opts.fixedCount != null) {
            return Math.max(0, this.opts.fixedCount);
        }
        const w = this.viewWidth || window.innerWidth;
        const h = this.viewHeight || window.innerHeight;
        const raw = Math.round(w * h * this.opts.density);
        return Math.max(this.opts.minParticles, Math.min(this.opts.maxParticles, raw));
    }

    init() {
        this.onResize();
        // onResize 内部已经调了 createParticles(computeCount())，此处不再重复创建
        // 仅做兜底：若 onResize 因尺寸异常（<=0）跳过了粒子创建，则延迟重试
        const w = this.viewWidth || window.innerWidth;
        const h = this.viewHeight || window.innerHeight;
        if (w <= 0 || h <= 0) {
            setTimeout(() => {
                if (this.viewWidth && this.viewWidth > 0) {
                    this.createParticles(this.computeCount());
                }
            }, 50);
        }
    }

    bindEvents() {
        window.addEventListener('resize', () => {
            if (this.resizeTimer) clearTimeout(this.resizeTimer);
            this.resizeTimer = setTimeout(() => this.onResize(), 150);
        });

        if (this.opts.mouseEnabled) {
            document.addEventListener('mousemove', (e) => {
                this.mouse.x = e.clientX;
                this.mouse.y = e.clientY;
            });
            document.addEventListener('mouseleave', () => {
                this.mouse.x = null;
                this.mouse.y = null;
            });
        }

        document.addEventListener('visibilitychange', () => {
            if (!document.hidden) {
                this.resume();
            }
        });

        window.addEventListener('pageshow', () => {
            setTimeout(() => this.resume(), 0);
        });

        // 监听主题切换：未自定义颜色时实时更新粒子配色
        this.themeObserver = new MutationObserver(() => {
            const dark = document.documentElement.getAttribute('data-theme') === 'dark';
            if (dark !== this.isDarkMode) {
                this.isDarkMode = dark;
                if (!Array.isArray(this.opts.colors)) {
                    const colors = this.resolveColors();
                    this.particles.forEach(p => {
                        p.color = colors[Math.floor(Math.random() * colors.length)];
                    });
                }
            }
        });
        this.themeObserver.observe(document.documentElement, {
            attributes: true,
            attributeFilter: ['data-theme']
        });
    }

    // 兼容旧调用名
    resize() {
        this.onResize();
    }

    onResize() {
        if (!this.canvas) return;
        const dpr = this.dpr;
        const w = window.innerWidth;
        const h = window.innerHeight;
        // 高 DPI 适配：物理像素 = 逻辑像素 × DPR，绘制坐标使用逻辑像素
        this.canvas.width = Math.floor(w * dpr);
        this.canvas.height = Math.floor(h * dpr);
        this.canvas.style.width = w + 'px';
        this.canvas.style.height = h + 'px';
        this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        this.viewWidth = w;
        this.viewHeight = h;

        // 按新尺寸增减粒子，保持密度一致
        const target = this.computeCount();
        if (this.particles.length < target) {
            this.createParticles(target - this.particles.length);
        } else if (this.particles.length > target) {
            this.particles.length = target;
        }
    }

    createParticles(count) {
        const colors = this.resolveColors();
        const [minSize, maxSize] = this.opts.sizeRange;
        const [minOp, maxOp] = this.opts.opacityRange;
        const speed = this.opts.speedScale;
        const w = this.viewWidth || window.innerWidth;
        const h = this.viewHeight || window.innerHeight;

        for (let i = 0; i < count; i++) {
            const baseOp = Math.random() * (maxOp - minOp) + minOp;
            this.particles.push({
                x: Math.random() * w,
                y: Math.random() * h,
                vx: (Math.random() - 0.5) * speed,
                vy: (Math.random() - 0.5) * speed,
                radius: Math.random() * (maxSize - minSize) + minSize,
                baseOpacity: baseOp,
                opacity: baseOp,                // 直接以 baseOpacity 可见，避免从透明渐入（看起来像从角落飘出）
                phase: Math.random() * Math.PI * 2,
                color: colors[Math.floor(Math.random() * colors.length)]
            });
        }
    }

    buildGrid() {
        const grid = new Map();
        const cellSize = this.gridCellSize;
        for (let i = 0; i < this.particles.length; i++) {
            const p = this.particles[i];
            const col = Math.floor(p.x / cellSize);
            const row = Math.floor(p.y / cellSize);
            const key = col + ',' + row;
            let cell = grid.get(key);
            if (!cell) {
                cell = [];
                grid.set(key, cell);
            }
            cell.push(i);
        }
        return grid;
    }

    update() {
        const w = this.viewWidth;
        const h = this.viewHeight;
        const mr = this.opts.mouseRadius;
        const mrSq = mr * mr;
        const mf = this.opts.mouseForce;
        // 鼠标在 (0,0) 时视为无效（页面刚加载、鼠标未移动），避免粒子被左上角排斥力推开
        const mouseOn = this.opts.mouseEnabled
            && this.mouse.x !== null && this.mouse.y !== null
            && (this.mouse.x > 0 || this.mouse.y > 0);
        const pulse = this.opts.pulse;
        const ps = this.opts.pulseSpeed;
        const pa = this.opts.pulseAmount;
        this.time += 1;

        for (let i = 0; i < this.particles.length; i++) {
            const p = this.particles[i];

            // 鼠标排斥 + 高亮增强
            let boost = 0;
            if (mouseOn) {
                const dx = p.x - this.mouse.x;
                const dy = p.y - this.mouse.y;
                const distSq = dx * dx + dy * dy;
                if (distSq < mrSq) {
                    const dist = Math.sqrt(distSq) || 0.0001;
                    const force = (mr - dist) / mr;
                    p.x += (dx / dist) * force * mf;
                    p.y += (dy / dist) * force * mf;
                    boost = force * 0.3;
                }
            }

            // 位移
            p.x += p.vx;
            p.y += p.vy;

            // 边界反弹
            if (p.x < 0 || p.x > w) p.vx *= -1;
            if (p.y < 0 || p.y > h) p.vy *= -1;
            p.x = Math.max(0, Math.min(w, p.x));
            p.y = Math.max(0, Math.min(h, p.y));

            // 透明度 = 基础呼吸脉动 + 鼠标增强
            const pulseOp = pulse
                ? p.baseOpacity + Math.sin(this.time * ps + p.phase) * pa
                : p.baseOpacity;
            p.opacity = Math.max(0, Math.min(0.85, pulseOp + boost));
        }
    }

    draw() {
        if (!this.ctx) return;
        const ctx = this.ctx;
        ctx.clearRect(0, 0, this.viewWidth, this.viewHeight);

        // 绘制粒子
        for (let i = 0; i < this.particles.length; i++) {
            const p = this.particles[i];
            ctx.beginPath();
            ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2);
            ctx.fillStyle = `rgba(${p.color}, ${p.opacity})`;
            ctx.fill();
        }

        // 绘制连线（空间网格加速，避免 O(n²)）
        const grid = this.buildGrid();
        const maxDistSq = this.connectionDistanceSq;
        const maxDist = this.connectionDistance;
        const connOp = this.opts.connectOpacity;
        const lw = this.opts.lineWidth;
        const drawn = new Set();

        grid.forEach((cellIndices, key) => {
            const parts = key.split(',');
            const col = Number(parts[0]);
            const row = Number(parts[1]);

            const neighborIndices = [];
            for (let dc = -1; dc <= 1; dc++) {
                for (let dr = -1; dr <= 1; dr++) {
                    const nCell = grid.get((col + dc) + ',' + (row + dr));
                    if (nCell) {
                        for (let k = 0; k < nCell.length; k++) {
                            neighborIndices.push(nCell[k]);
                        }
                    }
                }
            }

            for (let a = 0; a < cellIndices.length; a++) {
                const i = cellIndices[a];
                const pi = this.particles[i];
                for (let b = 0; b < neighborIndices.length; b++) {
                    const j = neighborIndices[b];
                    if (j <= i) continue;

                    const pairKey = i * 100000 + j;
                    if (drawn.has(pairKey)) continue;
                    drawn.add(pairKey);

                    const pj = this.particles[j];
                    const dx = pi.x - pj.x;
                    const dy = pi.y - pj.y;
                    const distSq = dx * dx + dy * dy;

                    if (distSq < maxDistSq) {
                        const distance = Math.sqrt(distSq);
                        const opacity = (1 - distance / maxDist) * connOp;
                        ctx.beginPath();
                        ctx.moveTo(pi.x, pi.y);
                        ctx.lineTo(pj.x, pj.y);
                        ctx.strokeStyle = `rgba(${pi.color}, ${opacity})`;
                        ctx.lineWidth = lw;
                        ctx.stroke();
                    }
                }
            }
        });
    }

    animate() {
        this.rafId = null;
        if (document.hidden) {
            return;
        }
        this.update();
        this.draw();
        this.rafId = requestAnimationFrame(() => this.animate());
    }

    resume() {
        if (!this.rafId) {
            this.animate();
        }
    }

    /** 销毁实例：停止动画、断开监听、清屏，供防重复初始化与页面卸载使用 */
    destroy() {
        if (this.rafId) {
            cancelAnimationFrame(this.rafId);
            this.rafId = null;
        }
        if (this.themeObserver) {
            this.themeObserver.disconnect();
            this.themeObserver = null;
        }
        if (this.resizeTimer) {
            clearTimeout(this.resizeTimer);
            this.resizeTimer = null;
        }
        if (this.ctx) {
            this.ctx.clearRect(0, 0, this.viewWidth || 0, this.viewHeight || 0);
        }
        this.particles = [];
        if (ParticleSystem._instances && this.canvas) {
            ParticleSystem._instances.delete(this.canvas);
        }
    }
}

