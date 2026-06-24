(function() {
    'use strict';
    document.addEventListener('DOMContentLoaded', function() {
        var header = document.getElementById('ilHeader');
        if (header) {
            var ticking = false;
            window.addEventListener('scroll', function() {
                if (!ticking) {
                    window.requestAnimationFrame(function() {
                        header.classList.toggle('il-header--scrolled', window.scrollY > 10);
                        ticking = false;
                    });
                    ticking = true;
                }
            }, { passive: true });
        }
    });
})();
