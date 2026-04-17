#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 texColor = texture(Sampler0, texCoord0);

    // Output: flat vertex color RGB, texture alpha only
    vec4 color = vec4(vertexColor.rgb, texColor.a * vertexColor.a);
    color *= ColorModulator;

    if (color.a < 0.1) {
        discard;
    }

    fragColor = color;
}
