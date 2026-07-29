(function () {
const toggle = document.getElementById('theme-toggle');
if (!toggle) return;
const root = document.documentElement;
const STORAGE_KEY = 'opendroid-theme';
const updateA11y = () => {
const isLight = root.getAttribute('data-theme') === 'light';
toggle.setAttribute('aria-pressed', String(isLight));
toggle.setAttribute('aria-label', isLight ? 'Enable dark mode' : 'Enable light mode');
};
updateA11y();
toggle.addEventListener('click', () => {
const next = root.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
root.setAttribute('data-theme', next);
try { localStorage.setItem(STORAGE_KEY, next); } catch (e) { /* storage unavailable, ignore */ }
updateA11y();
});
})();
const hamburger = document.getElementById('nav-hamburger');
const navLinks = document.getElementById('nav-links');
if (hamburger && navLinks) {
hamburger.addEventListener('click', () => {
hamburger.classList.toggle('active');
navLinks.classList.toggle('open');
});
document.addEventListener('click', (e) => {
if (!hamburger.contains(e.target) && !navLinks.contains(e.target)) {
hamburger.classList.remove('active');
navLinks.classList.remove('open');
}
});
}
const revealElements = document.querySelectorAll('.reveal');
const revealObserver = new IntersectionObserver((entries) => {
entries.forEach(entry => {
if (entry.isIntersecting) {
entry.target.classList.add('visible');
}
});
}, { threshold: 0.1, rootMargin: '0px 0px -40px 0px' });
revealElements.forEach(el => revealObserver.observe(el));
document.querySelectorAll('.faq-question').forEach(btn => {
btn.addEventListener('click', () => {
const item = btn.closest('.faq-item');
const answer = item.querySelector('.faq-answer');
const isOpen = item.classList.contains('open');
document.querySelectorAll('.faq-item.open').forEach(openItem => {
openItem.classList.remove('open');
openItem.querySelector('.faq-answer').style.maxHeight = '0';
});
if (!isOpen) {
item.classList.add('open');
answer.style.maxHeight = answer.scrollHeight + 'px';
}
});
});
const navbar = document.getElementById('navbar');
if (navbar) {
window.addEventListener('scroll', () => {
navbar.classList.toggle('scrolled', window.scrollY > 10);
});
}
