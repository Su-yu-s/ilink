// iLink 首页沉浸叙事动效

(function () {
    'use strict';

    if (!document.body || document.body.getAttribute('data-app-page') !== 'home') return;

    var reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    document.body.classList.add('home-js');
    if (!reduceMotion) document.body.classList.add('home-motion-ready');

    function ready(fn) {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', fn);
        } else {
            fn();
        }
    }

    ready(function () {
        splitHeroTitle();
        initReveals();
        initActivityFeed();
        initMagneticButtons();
        initCtaForm();
        initScrollProgress();
        initCursorFollower();
        if (!reduceMotion) initParticleCanvas();
        if (!reduceMotion) ensureGsap(initGsapMotion);
    });

    function splitHeroTitle() {
        document.querySelectorAll('.home-split-line').forEach(function (line) {
            var text = line.textContent || '';
            line.innerHTML = '';
            Array.from(text).forEach(function (char) {
                var span = document.createElement('span');
                span.className = 'home-char';
                span.textContent = char;
                line.appendChild(span);
            });
        });
    }

    function initReveals() {
        var items = Array.prototype.slice.call(document.querySelectorAll('.home-reveal'));
        if (!items.length) return;

        if (reduceMotion || !('IntersectionObserver' in window)) {
            items.forEach(function (item) { item.classList.add('is-visible'); });
            return;
        }

        var observer = new IntersectionObserver(function (entries) {
            entries.forEach(function (entry) {
                if (!entry.isIntersecting) return;
                entry.target.classList.add('is-visible');
                observer.unobserve(entry.target);
            });
        }, { threshold: 0.18, rootMargin: '0px 0px -10% 0px' });

        items.forEach(function (item) { observer.observe(item); });
    }

    function ensureGsap(done) {
        if (window.gsap) {
            ensureScrollTrigger(done);
            return;
        }

        loadScript('/lib/gsap.min.js?v=3.12.5', function () {
            ensureScrollTrigger(done);
        }, done);
    }

    function ensureScrollTrigger(done) {
        if (!window.gsap || window.ScrollTrigger) {
            done();
            return;
        }

        loadScript('/lib/ScrollTrigger.min.js?v=3.12.5', done, done);
    }

    function loadScript(src, onload, onerror) {
        var script = document.createElement('script');
        script.src = src;
        script.onload = onload;
        script.onerror = onerror;
        document.head.appendChild(script);
    }

    function initGsapMotion() {
        if (!window.gsap) return;
        var gsap = window.gsap;
        var ScrollTrigger = window.ScrollTrigger;
        if (ScrollTrigger) gsap.registerPlugin(ScrollTrigger);

        // 首屏标题逐字淡入，减少模板感，制造“开场”节奏。
        gsap.fromTo('.home-char',
            { opacity: 0, y: 36 },
            { opacity: 1, y: 0, duration: 0.9, stagger: 0.045, ease: 'power3.out', delay: 0.12 }
        );

        if (!ScrollTrigger) return;

        gsap.utils.toArray('.home-reveal').forEach(function (el) {
            if (el.closest('.home-hero')) return;
            gsap.fromTo(el,
                { opacity: 0, y: 30 },
                {
                    opacity: 1,
                    y: 0,
                    duration: 0.82,
                    ease: 'power3.out',
                    scrollTrigger: {
                        trigger: el,
                        start: 'top 84%',
                        once: true
                    }
                }
            );
        });

        // 图片视差只动 transform，不碰 layout，避免滚动卡顿。
        gsap.utils.toArray('.home-bento__media img, .home-mag-tile__inner img').forEach(function (img) {
            gsap.fromTo(img,
                { yPercent: -4 },
                {
                    yPercent: 4,
                    ease: 'none',
                    scrollTrigger: {
                        trigger: img,
                        start: 'top bottom',
                        end: 'bottom top',
                        scrub: true
                    }
                }
            );
        });
    }

    function initParticleCanvas() {
        var canvas = document.getElementById('homeParticleCanvas');
        if (!canvas) return;
        var ctx = canvas.getContext('2d');
        if (!ctx) return;

        var particles = [];
        var mouse = { x: -9999, y: -9999 };
        var rafId = null;
        var width = 0;
        var height = 0;
        var dpr = Math.min(window.devicePixelRatio || 1, 2);

        function particleCount() {
            if (window.innerWidth < 640) return 36;
            if (window.innerWidth < 1024) return 58;
            return 84;
        }

        function resize() {
            var rect = canvas.getBoundingClientRect();
            width = Math.max(1, Math.floor(rect.width));
            height = Math.max(1, Math.floor(rect.height));
            canvas.width = Math.floor(width * dpr);
            canvas.height = Math.floor(height * dpr);
            ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
            createParticles();
        }

        function createParticles() {
            var count = particleCount();
            particles = [];
            for (var i = 0; i < count; i++) {
                particles.push({
                    x: Math.random() * width,
                    y: Math.random() * height,
                    vx: (Math.random() - 0.5) * 0.34,
                    vy: (Math.random() - 0.5) * 0.34,
                    r: Math.random() * 1.6 + 0.8
                });
            }
        }

        function draw() {
            ctx.clearRect(0, 0, width, height);
            ctx.fillStyle = 'rgba(23, 21, 17, 0.48)';
            ctx.strokeStyle = 'rgba(23, 21, 17, 0.16)';
            ctx.lineWidth = 1;

            for (var i = 0; i < particles.length; i++) {
                var p = particles[i];
                var dxMouse = p.x - mouse.x;
                var dyMouse = p.y - mouse.y;
                var mouseDist = Math.sqrt(dxMouse * dxMouse + dyMouse * dyMouse);
                if (mouseDist < 120) {
                    p.vx += dxMouse / 12000;
                    p.vy += dyMouse / 12000;
                }

                p.x += p.vx;
                p.y += p.vy;
                p.vx *= 0.995;
                p.vy *= 0.995;

                if (p.x < -20) p.x = width + 20;
                if (p.x > width + 20) p.x = -20;
                if (p.y < -20) p.y = height + 20;
                if (p.y > height + 20) p.y = -20;

                ctx.beginPath();
                ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
                ctx.fill();

                for (var j = i + 1; j < particles.length; j++) {
                    var q = particles[j];
                    var dx = p.x - q.x;
                    var dy = p.y - q.y;
                    var dist = Math.sqrt(dx * dx + dy * dy);
                    if (dist > 132) continue;
                    ctx.globalAlpha = Math.max(0, 1 - dist / 132) * 0.72;
                    ctx.beginPath();
                    ctx.moveTo(p.x, p.y);
                    ctx.lineTo(q.x, q.y);
                    ctx.stroke();
                    ctx.globalAlpha = 1;
                }
            }

            rafId = window.requestAnimationFrame(draw);
        }

        window.addEventListener('resize', debounce(resize, 160), { passive: true });
        window.addEventListener('pointermove', function (event) {
            var rect = canvas.getBoundingClientRect();
            mouse.x = event.clientX - rect.left;
            mouse.y = event.clientY - rect.top;
        }, { passive: true });
        window.addEventListener('pointerleave', function () {
            mouse.x = -9999;
            mouse.y = -9999;
        }, { passive: true });
        document.addEventListener('visibilitychange', function () {
            if (document.hidden && rafId) {
                cancelAnimationFrame(rafId);
                rafId = null;
            } else if (!document.hidden && !rafId) {
                draw();
            }
        });

        resize();
        draw();
    }

    function initActivityFeed() {
        var items = Array.prototype.slice.call(document.querySelectorAll('.home-feed-item'));
        if (items.length < 2 || reduceMotion) return;
        var index = 0;
        window.setInterval(function () {
            items[index].classList.remove('is-active');
            index = (index + 1) % items.length;
            items[index].classList.add('is-active');
        }, 2800);
    }

    function initMagneticButtons() {
        if (reduceMotion || !window.matchMedia || !window.matchMedia('(pointer: fine)').matches) return;
        document.querySelectorAll('.home-magnetic').forEach(function (el) {
            el.addEventListener('pointermove', function (event) {
                var rect = el.getBoundingClientRect();
                var x = (event.clientX - rect.left - rect.width / 2) * 0.18;
                var y = (event.clientY - rect.top - rect.height / 2) * 0.18;
                el.style.transform = 'translate(' + x.toFixed(1) + 'px, ' + y.toFixed(1) + 'px)';
            });
            el.addEventListener('pointerleave', function () {
                el.style.transform = '';
            });
        });
    }

    function initCtaForm() {
        var form = document.querySelector('.home-cta-form');
        if (!form) return;
        form.addEventListener('submit', function (event) {
            var input = form.querySelector('input');
            if (!input || input.value.trim()) return;
            event.preventDefault();
            window.location.href = '/team-market.html';
        });
    }

    function initScrollProgress() {
        var bar = document.getElementById('homeScrollProgress');
        if (!bar) return;
        var ticking = false;
        function update() {
            var scrollTop = window.scrollY || window.pageYOffset;
            var docHeight = document.documentElement.scrollHeight - window.innerHeight;
            var progress = docHeight > 0 ? (scrollTop / docHeight) * 100 : 0;
            bar.style.width = progress + '%';
            ticking = false;
        }
        window.addEventListener('scroll', function () {
            if (!ticking) {
                window.requestAnimationFrame(update);
                ticking = true;
            }
        }, { passive: true });
    }

    function initCursorFollower() {
        if (reduceMotion || !window.matchMedia || !window.matchMedia('(pointer: fine)').matches) return;
        var cursor = document.getElementById('homeCursor');
        if (!cursor) return;
        var mx = -100, my = -100, cx = -100, cy = -100;
        var visible = false;
        var rafId = null;

        function loop() {
            cx += (mx - cx) * 0.35;
            cy += (my - cy) * 0.35;
            cursor.style.transform = 'translate(' + cx.toFixed(1) + 'px, ' + cy.toFixed(1) + 'px)';
            rafId = window.requestAnimationFrame(loop);
        }

        window.addEventListener('pointermove', function (e) {
            mx = e.clientX;
            my = e.clientY;
            if (!visible) {
                visible = true;
                cursor.classList.add('is-visible');
                cx = mx;
                cy = my;
                loop();
            }
        }, { passive: true });

        window.addEventListener('pointerleave', function () {
            visible = false;
            cursor.classList.remove('is-visible');
            if (rafId) { cancelAnimationFrame(rafId); rafId = null; }
        }, { passive: true });

        document.addEventListener('visibilitychange', function () {
            if (document.hidden && rafId) {
                cancelAnimationFrame(rafId);
                rafId = null;
            } else if (!document.hidden && visible && !rafId) {
                loop();
            }
        });

        var hoverEls = document.querySelectorAll('a, button, [data-magnetic]');
        for (var i = 0; i < hoverEls.length; i++) {
            hoverEls[i].addEventListener('pointerenter', function () { cursor.classList.add('is-hover'); });
            hoverEls[i].addEventListener('pointerleave', function () { cursor.classList.remove('is-hover'); });
        }

        document.addEventListener('mousedown', function () { cursor.classList.add('is-click'); });
        document.addEventListener('mouseup', function () { cursor.classList.remove('is-click'); });
    }

    function debounce(fn, delay) {
        var timer = null;
        return function () {
            var args = arguments;
            clearTimeout(timer);
            timer = setTimeout(function () { fn.apply(null, args); }, delay);
        };
    }

    // ── Desktop hamburger menu (home page only) ──
    (function initDesktopMenu() {
        var body = document.body;
        if (!body || body.getAttribute('data-app-page') !== 'home') return;

        var hamburger = document.getElementById('desktopHamburger');
        var menuPanel = document.getElementById('desktopMenuPanel');
        if (!hamburger || !menuPanel) return;

        hamburger.addEventListener('click', function(e) {
            e.stopPropagation();
            var isOpen = menuPanel.classList.toggle('il-desktop-menu--open');
            hamburger.setAttribute('aria-expanded', String(isOpen));
            menuPanel.setAttribute('aria-hidden', String(!isOpen));
            document.body.classList.toggle('desktop-menu-open', isOpen);
        });

        document.addEventListener('click', function(e) {
            if (!menuPanel.classList.contains('il-desktop-menu--open')) return;
            if (e.target.closest('#desktopMenuPanel') || e.target.closest('#desktopHamburger')) return;
            menuPanel.classList.remove('il-desktop-menu--open');
            hamburger.setAttribute('aria-expanded', 'false');
            menuPanel.setAttribute('aria-hidden', 'true');
            document.body.classList.remove('desktop-menu-open');
        });

        document.addEventListener('keydown', function(e) {
            if (e.key === 'Escape' && menuPanel.classList.contains('il-desktop-menu--open')) {
                menuPanel.classList.remove('il-desktop-menu--open');
                hamburger.setAttribute('aria-expanded', 'false');
                menuPanel.setAttribute('aria-hidden', 'true');
                document.body.classList.remove('desktop-menu-open');
            }
        });
    })();

    // ── Home page: hide navbar on first paint, show on scroll ──
    (function initHomeNavbar() {
        var body = document.body;
        if (!body || body.getAttribute('data-app-page') !== 'home') return;

        var header = document.getElementById('ilHeader');
        if (!header) return;

        var THRESHOLD = 100;
        var ticking = false;

        function applyNavState() {
            var scrollY = window.scrollY || window.pageYOffset;
            var expanded = scrollY >= THRESHOLD;
            header.classList.toggle('il-header--home-expanded', expanded);
            header.classList.toggle('il-header--home-compact', !expanded);
        }

        function onScroll() {
            if (!ticking) {
                window.requestAnimationFrame(function() {
                    applyNavState();
                    ticking = false;
                });
                ticking = true;
            }
        }

        applyNavState();
        window.addEventListener('scroll', onScroll, { passive: true });
    })();
})();
